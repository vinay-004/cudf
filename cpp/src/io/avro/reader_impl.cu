/*
 * Copyright (c) 2020-2021, NVIDIA CORPORATION.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "avro.h"
#include "avro_gpu.h"

#include <io/comp/gpuinflate.h>
#include <io/utilities/column_buffer.hpp>
#include <io/utilities/hostdevice_vector.hpp>

#include <cudf/detail/null_mask.hpp>
#include <cudf/detail/utilities/vector_factories.hpp>
#include <cudf/io/datasource.hpp>
#include <cudf/io/detail/avro.hpp>
#include <cudf/table/table.hpp>
#include <cudf/utilities/error.hpp>
#include <cudf/utilities/span.hpp>
#include <cudf/utilities/traits.hpp>

#include <rmm/cuda_stream_view.hpp>
#include <rmm/device_buffer.hpp>
#include <rmm/device_uvector.hpp>

#include <memory>
#include <string>
#include <utility>
#include <vector>

using cudf::device_span;

namespace cudf {
namespace io {
namespace detail {
namespace avro {

// Import functionality that's independent of legacy code
using namespace cudf::io::avro;
using namespace cudf::io;

namespace {
/**
 * @brief Function that translates Avro data kind to cuDF type enum
 */
type_id to_type_id(avro::schema_entry const* col)
{
  switch (col->kind) {
    case avro::type_boolean: return type_id::BOOL8;
    case avro::type_int: return type_id::INT32;
    case avro::type_long: return type_id::INT64;
    case avro::type_float: return type_id::FLOAT32;
    case avro::type_double: return type_id::FLOAT64;
    case avro::type_bytes:
    case avro::type_string: return type_id::STRING;
    case avro::type_enum: return (!col->symbols.empty()) ? type_id::STRING : type_id::INT32;
    default: return type_id::EMPTY;
  }
}

}  // namespace

/**
 * @brief A helper wrapper for Avro file metadata. Provides some additional
 * convenience methods for initializing and accessing the metadata and schema
 */
class metadata : public file_metadata {
 public:
  explicit metadata(datasource* const src) : source(src) {}

  /**
   * @brief Initializes the parser and filters down to a subset of rows
   *
   * @param[in,out] row_start Starting row of the selection
   * @param[in,out] row_count Total number of rows selected
   */
  void init_and_select_rows(int& row_start, int& row_count)
  {
    auto const buffer = source->host_read(0, source->size());
    avro::container pod(buffer->data(), buffer->size());
    CUDF_EXPECTS(pod.parse(this, row_count, row_start), "Cannot parse metadata");
    row_start = skip_rows;
    row_count = num_rows;
  }

  /**
   * @brief Filters and reduces down to a selection of columns
   *
   * @param[in] use_names List of column names to select
   *
   * @return List of column names
   */
  auto select_columns(std::vector<std::string> use_names)
  {
    std::vector<std::pair<int, std::string>> selection;

    auto const num_avro_columns = static_cast<int>(columns.size());
    if (!use_names.empty()) {
      int index = 0;
      for (auto const& use_name : use_names) {
        for (int i = 0; i < num_avro_columns; ++i, ++index) {
          if (index >= num_avro_columns) { index = 0; }
          if (columns[index].name == use_name &&
              type_id::EMPTY != to_type_id(&schema[columns[index].schema_data_idx])) {
            selection.emplace_back(index, columns[index].name);
            index++;
            break;
          }
        }
      }
      CUDF_EXPECTS(selection.size() > 0, "Filtered out all columns");
    } else {
      for (int i = 0; i < num_avro_columns; ++i) {
        // Exclude array columns (unsupported)
        bool column_in_array = false;
        for (int parent_idx = schema[columns[i].schema_data_idx].parent_idx; parent_idx > 0;
             parent_idx     = schema[parent_idx].parent_idx) {
          if (schema[parent_idx].kind == avro::type_array) {
            column_in_array = true;
            break;
          }
        }
        if (!column_in_array) {
          auto col_type = to_type_id(&schema[columns[i].schema_data_idx]);
          CUDF_EXPECTS(col_type != type_id::EMPTY, "Unsupported data type");
          selection.emplace_back(i, columns[i].name);
        }
      }
    }

    return selection;
  }

 private:
  datasource* const source;
};

rmm::device_buffer decompress_data(datasource& source,
                                   metadata& meta,
                                   rmm::device_buffer const& comp_block_data,
                                   rmm::cuda_stream_view stream)
{
  size_t uncompressed_data_size = 0;

  auto inflate_in  = hostdevice_vector<gpu_inflate_input_s>(meta.block_list.size());
  auto inflate_out = hostdevice_vector<gpu_inflate_status_s>(meta.block_list.size());

  if (meta.codec == "deflate") {
    // Guess an initial maximum uncompressed block size
    uint32_t initial_blk_len = (meta.max_block_size * 2 + 0xfff) & ~0xfff;
    uncompressed_data_size   = initial_blk_len * meta.block_list.size();
    for (size_t i = 0; i < inflate_in.size(); ++i) {
      inflate_in[i].dstSize = initial_blk_len;
    }
  } else if (meta.codec == "snappy") {
    // Extract the uncompressed length from the snappy stream
    for (size_t i = 0; i < meta.block_list.size(); i++) {
      auto const buffer  = source.host_read(meta.block_list[i].offset, 4);
      uint8_t const* blk = buffer->data();
      uint32_t blk_len   = blk[0];
      if (blk_len > 0x7f) {
        blk_len = (blk_len & 0x7f) | (blk[1] << 7);
        if (blk_len > 0x3fff) {
          blk_len = (blk_len & 0x3fff) | (blk[2] << 14);
          if (blk_len > 0x1fffff) { blk_len = (blk_len & 0x1fffff) | (blk[3] << 21); }
        }
      }
      inflate_in[i].dstSize = blk_len;
      uncompressed_data_size += blk_len;
    }
  } else {
    CUDF_FAIL("Unsupported compression codec\n");
  }

  rmm::device_buffer decomp_block_data(uncompressed_data_size, stream);

  auto const base_offset = meta.block_list[0].offset;
  for (size_t i = 0, dst_pos = 0; i < meta.block_list.size(); i++) {
    auto const src_pos = meta.block_list[i].offset - base_offset;

    inflate_in[i].srcDevice = static_cast<uint8_t const*>(comp_block_data.data()) + src_pos;
    inflate_in[i].srcSize   = meta.block_list[i].size;
    inflate_in[i].dstDevice = static_cast<uint8_t*>(decomp_block_data.data()) + dst_pos;

    // Update blocks offsets & sizes to refer to uncompressed data
    meta.block_list[i].offset = dst_pos;
    meta.block_list[i].size   = static_cast<uint32_t>(inflate_in[i].dstSize);
    dst_pos += meta.block_list[i].size;
  }

  for (int loop_cnt = 0; loop_cnt < 2; loop_cnt++) {
    inflate_in.host_to_device(stream);
    CUDA_TRY(
      cudaMemsetAsync(inflate_out.device_ptr(), 0, inflate_out.memory_size(), stream.value()));
    if (meta.codec == "deflate") {
      CUDA_TRY(gpuinflate(
        inflate_in.device_ptr(), inflate_out.device_ptr(), inflate_in.size(), 0, stream));
    } else if (meta.codec == "snappy") {
      CUDA_TRY(
        gpu_unsnap(inflate_in.device_ptr(), inflate_out.device_ptr(), inflate_in.size(), stream));
    } else {
      CUDF_FAIL("Unsupported compression codec\n");
    }
    inflate_out.device_to_host(stream, true);

    // Check if larger output is required, as it's not known ahead of time
    if (meta.codec == "deflate" && !loop_cnt) {
      size_t actual_uncompressed_size = 0;
      for (size_t i = 0; i < meta.block_list.size(); i++) {
        // If error status is 1 (buffer too small), the `bytes_written` field
        // is actually contains the uncompressed data size
        if (inflate_out[i].status == 1 && inflate_out[i].bytes_written > inflate_in[i].dstSize) {
          inflate_in[i].dstSize = inflate_out[i].bytes_written;
        }
        actual_uncompressed_size += inflate_in[i].dstSize;
      }
      if (actual_uncompressed_size > uncompressed_data_size) {
        decomp_block_data.resize(actual_uncompressed_size, stream);
        for (size_t i = 0, dst_pos = 0; i < meta.block_list.size(); i++) {
          auto dst_base           = static_cast<uint8_t*>(decomp_block_data.data());
          inflate_in[i].dstDevice = dst_base + dst_pos;

          meta.block_list[i].offset = dst_pos;
          meta.block_list[i].size   = static_cast<uint32_t>(inflate_in[i].dstSize);
          dst_pos += meta.block_list[i].size;
        }
      } else {
        break;
      }
    } else {
      break;
    }
  }

  return decomp_block_data;
}

std::vector<column_buffer> decode_data(metadata& meta,
                                       rmm::device_buffer const& block_data,
                                       std::vector<std::pair<uint32_t, uint32_t>> const& dict,
                                       device_span<string_index_pair const> global_dictionary,
                                       size_t num_rows,
                                       std::vector<std::pair<int, std::string>> const& selection,
                                       std::vector<data_type> const& column_types,
                                       rmm::cuda_stream_view stream,
                                       rmm::mr::device_memory_resource* mr)
{
  auto out_buffers = std::vector<column_buffer>();

  for (size_t i = 0; i < column_types.size(); ++i) {
    auto col_idx     = selection[i].first;
    bool is_nullable = (meta.columns[col_idx].schema_null_idx >= 0);
    out_buffers.emplace_back(column_types[i], num_rows, is_nullable, stream, mr);
  }

  // Build gpu schema
  auto schema_desc = hostdevice_vector<gpu::schemadesc_s>(meta.schema.size());

  uint32_t min_row_data_size = 0;
  int skip_field_cnt         = 0;

  for (size_t i = 0; i < meta.schema.size(); i++) {
    type_kind_e kind = meta.schema[i].kind;
    if (skip_field_cnt != 0) {
      // Exclude union and array members from min_row_data_size
      skip_field_cnt += meta.schema[i].num_children - 1;
    } else {
      switch (kind) {
        case type_union:
        case type_array:
          skip_field_cnt = meta.schema[i].num_children;
          // fall through
        case type_boolean:
        case type_int:
        case type_long:
        case type_bytes:
        case type_string:
        case type_enum: min_row_data_size += 1; break;
        case type_float: min_row_data_size += 4; break;
        case type_double: min_row_data_size += 8; break;
        default: break;
      }
    }
    if (kind == type_enum && !meta.schema[i].symbols.size()) { kind = type_int; }
    schema_desc[i].kind = kind;
    schema_desc[i].count =
      (kind == type_enum) ? 0 : static_cast<uint32_t>(meta.schema[i].num_children);
    schema_desc[i].dataptr = nullptr;
    CUDF_EXPECTS(kind != type_union || meta.schema[i].num_children < 2 ||
                   (meta.schema[i].num_children == 2 &&
                    (meta.schema[i + 1].kind == type_null || meta.schema[i + 2].kind == type_null)),
                 "Union with non-null type not currently supported");
  }
  std::vector<void*> valid_alias(out_buffers.size(), nullptr);
  for (size_t i = 0; i < out_buffers.size(); i++) {
    auto const col_idx  = selection[i].first;
    int schema_data_idx = meta.columns[col_idx].schema_data_idx;
    int schema_null_idx = meta.columns[col_idx].schema_null_idx;

    schema_desc[schema_data_idx].dataptr = out_buffers[i].data();
    if (schema_null_idx >= 0) {
      if (!schema_desc[schema_null_idx].dataptr) {
        schema_desc[schema_null_idx].dataptr = out_buffers[i].null_mask();
      } else {
        valid_alias[i] = schema_desc[schema_null_idx].dataptr;
      }
    }
    if (meta.schema[schema_data_idx].kind == type_enum) {
      schema_desc[schema_data_idx].count = dict[i].first;
    }
    if (out_buffers[i].null_mask_size()) {
      cudf::detail::set_null_mask(out_buffers[i].null_mask(), 0, num_rows, true, stream);
    }
  }

  auto block_list = cudf::detail::make_device_uvector_async(meta.block_list, stream);

  schema_desc.host_to_device(stream);

  gpu::DecodeAvroColumnData(block_list,
                            schema_desc.device_ptr(),
                            global_dictionary,
                            static_cast<uint8_t const*>(block_data.data()),
                            static_cast<uint32_t>(schema_desc.size()),
                            meta.num_rows,
                            meta.skip_rows,
                            min_row_data_size,
                            stream);

  // Copy valid bits that are shared between columns
  for (size_t i = 0; i < out_buffers.size(); i++) {
    if (valid_alias[i] != nullptr) {
      CUDA_TRY(cudaMemcpyAsync(out_buffers[i].null_mask(),
                               valid_alias[i],
                               out_buffers[i].null_mask_size(),
                               cudaMemcpyHostToDevice,
                               stream.value()));
    }
  }
  schema_desc.device_to_host(stream, true);

  for (size_t i = 0; i < out_buffers.size(); i++) {
    auto const col_idx          = selection[i].first;
    auto const schema_null_idx  = meta.columns[col_idx].schema_null_idx;
    out_buffers[i].null_count() = (schema_null_idx >= 0) ? schema_desc[schema_null_idx].count : 0;
  }

  return out_buffers;
}

table_with_metadata read_avro(std::unique_ptr<cudf::io::datasource>&& source,
                              avro_reader_options const& options,
                              rmm::cuda_stream_view stream,
                              rmm::mr::device_memory_resource* mr)
{
  auto skip_rows = options.get_skip_rows();
  auto num_rows  = options.get_num_rows();
  num_rows       = (num_rows != 0) ? num_rows : -1;
  std::vector<std::unique_ptr<column>> out_columns;
  table_metadata metadata_out;

  // Open the source Avro dataset metadata
  auto meta = metadata(source.get());

  // Select and read partial metadata / schema within the subset of rows
  meta.init_and_select_rows(skip_rows, num_rows);

  // Select only columns required by the options
  auto selected_columns = meta.select_columns(options.get_columns());
  if (selected_columns.size() != 0) {
    // Get a list of column data types
    std::vector<data_type> column_types;
    for (auto const& col : selected_columns) {
      auto& col_schema = meta.schema[meta.columns[col.first].schema_data_idx];

      auto col_type = to_type_id(&col_schema);
      CUDF_EXPECTS(col_type != type_id::EMPTY, "Unknown type");
      column_types.emplace_back(col_type);
    }

    if (meta.total_data_size > 0) {
      rmm::device_buffer block_data;
      if (source->is_device_read_preferred(meta.total_data_size)) {
        block_data      = rmm::device_buffer{meta.total_data_size, stream};
        auto read_bytes = source->device_read(meta.block_list[0].offset,
                                              meta.total_data_size,
                                              static_cast<uint8_t*>(block_data.data()),
                                              stream);
        block_data.resize(read_bytes, stream);
      } else {
        auto const buffer = source->host_read(meta.block_list[0].offset, meta.total_data_size);
        block_data        = rmm::device_buffer{buffer->data(), buffer->size(), stream};
      }

      if (meta.codec != "" && meta.codec != "null") {
        auto decomp_block_data = decompress_data(*source, meta, block_data, stream);
        block_data             = std::move(decomp_block_data);
      } else {
        auto dst_ofs = meta.block_list[0].offset;
        for (size_t i = 0; i < meta.block_list.size(); i++) {
          meta.block_list[i].offset -= dst_ofs;
        }
      }

      size_t total_dictionary_entries = 0;
      size_t dictionary_data_size     = 0;

      auto dict = std::vector<std::pair<uint32_t, uint32_t>>(column_types.size());

      for (size_t i = 0; i < column_types.size(); ++i) {
        auto col_idx     = selected_columns[i].first;
        auto& col_schema = meta.schema[meta.columns[col_idx].schema_data_idx];
        dict[i].first    = static_cast<uint32_t>(total_dictionary_entries);
        dict[i].second   = static_cast<uint32_t>(col_schema.symbols.size());
        total_dictionary_entries += dict[i].second;
        for (auto const& sym : col_schema.symbols) {
          dictionary_data_size += sym.length();
        }
      }

      auto d_global_dict      = rmm::device_uvector<string_index_pair>(0, stream);
      auto d_global_dict_data = rmm::device_uvector<char>(0, stream);

      if (total_dictionary_entries > 0) {
        auto h_global_dict      = std::vector<string_index_pair>(total_dictionary_entries);
        auto h_global_dict_data = std::vector<char>(dictionary_data_size);
        size_t dict_pos         = 0;

        for (size_t i = 0; i < column_types.size(); ++i) {
          auto const col_idx          = selected_columns[i].first;
          auto const& col_schema      = meta.schema[meta.columns[col_idx].schema_data_idx];
          auto const col_dict_entries = &(h_global_dict[dict[i].first]);
          for (size_t j = 0; j < dict[i].second; j++) {
            auto const& symbols = col_schema.symbols[j];

            auto const data_dst        = h_global_dict_data.data() + dict_pos;
            auto const len             = symbols.length();
            col_dict_entries[j].first  = data_dst;
            col_dict_entries[j].second = len;

            std::copy(symbols.c_str(), symbols.c_str() + len, data_dst);
            dict_pos += len;
          }
        }

        d_global_dict      = cudf::detail::make_device_uvector_async(h_global_dict, stream);
        d_global_dict_data = cudf::detail::make_device_uvector_async(h_global_dict_data, stream);

        stream.synchronize();
      }

      auto out_buffers = decode_data(meta,
                                     block_data,
                                     dict,
                                     d_global_dict,
                                     num_rows,
                                     selected_columns,
                                     column_types,
                                     stream,
                                     mr);

      for (size_t i = 0; i < column_types.size(); ++i) {
        out_columns.emplace_back(make_column(out_buffers[i], nullptr, stream, mr));
      }
    } else {
      // Create empty columns
      for (size_t i = 0; i < column_types.size(); ++i) {
        out_columns.emplace_back(make_empty_column(column_types[i]));
      }
    }
  }

  // Return column names (must match order of returned columns)
  metadata_out.column_names.resize(selected_columns.size());
  for (size_t i = 0; i < selected_columns.size(); i++) {
    metadata_out.column_names[i] = selected_columns[i].second;
  }
  // Return user metadata
  metadata_out.user_data = meta.user_data;

  return {std::make_unique<table>(std::move(out_columns)), std::move(metadata_out)};
}

}  // namespace avro
}  // namespace detail
}  // namespace io
}  // namespace cudf
