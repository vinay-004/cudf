/*
 * Copyright (c) 2019-2021, NVIDIA CORPORATION.
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

/**
 * @file writer_impl.cu
 * @brief cuDF-IO ORC writer class implementation
 */

#include "writer_impl.hpp"

#include <io/statistics/column_statistics.cuh>
#include <io/utilities/column_utils.cuh>

#include <cudf/detail/iterator.cuh>
#include <cudf/detail/utilities/cuda.cuh>
#include <cudf/detail/utilities/vector_factories.hpp>
#include <cudf/null_mask.hpp>
#include <cudf/strings/strings_column_view.hpp>
#include <cudf/utilities/bit.hpp>
#include <cudf/utilities/span.hpp>

#include <rmm/cuda_stream_view.hpp>
#include <rmm/device_buffer.hpp>
#include <rmm/device_uvector.hpp>

#include <nvcomp/snappy.h>

#include <algorithm>
#include <cstring>
#include <numeric>
#include <utility>

namespace cudf {
namespace io {
namespace detail {
namespace orc {
using namespace cudf::io::orc;
using namespace cudf::io;

struct row_group_index_info {
  int32_t pos       = -1;  // Position
  int32_t blk_pos   = -1;  // Block Position
  int32_t comp_pos  = -1;  // Compressed Position
  int32_t comp_size = -1;  // Compressed size
};

namespace {
/**
 * @brief Helper for pinned host memory
 */
template <typename T>
using pinned_buffer = std::unique_ptr<T, decltype(&cudaFreeHost)>;

/**
 * @brief Function that translates GDF compression to ORC compression
 */
orc::CompressionKind to_orc_compression(compression_type compression)
{
  switch (compression) {
    case compression_type::AUTO:
    case compression_type::SNAPPY: return orc::CompressionKind::SNAPPY;
    case compression_type::NONE: return orc::CompressionKind::NONE;
    default: CUDF_EXPECTS(false, "Unsupported compression type"); return orc::CompressionKind::NONE;
  }
}

/**
 * @brief Function that translates GDF dtype to ORC datatype
 */
constexpr orc::TypeKind to_orc_type(cudf::type_id id)
{
  switch (id) {
    case cudf::type_id::INT8: return TypeKind::BYTE;
    case cudf::type_id::INT16: return TypeKind::SHORT;
    case cudf::type_id::INT32: return TypeKind::INT;
    case cudf::type_id::INT64: return TypeKind::LONG;
    case cudf::type_id::FLOAT32: return TypeKind::FLOAT;
    case cudf::type_id::FLOAT64: return TypeKind::DOUBLE;
    case cudf::type_id::BOOL8: return TypeKind::BOOLEAN;
    case cudf::type_id::TIMESTAMP_DAYS: return TypeKind::DATE;
    case cudf::type_id::TIMESTAMP_SECONDS:
    case cudf::type_id::TIMESTAMP_MICROSECONDS:
    case cudf::type_id::TIMESTAMP_MILLISECONDS:
    case cudf::type_id::TIMESTAMP_NANOSECONDS: return TypeKind::TIMESTAMP;
    case cudf::type_id::STRING: return TypeKind::STRING;
    case cudf::type_id::DECIMAL32:
    case cudf::type_id::DECIMAL64: return TypeKind::DECIMAL;
    case cudf::type_id::LIST: return TypeKind::LIST;
    case cudf::type_id::STRUCT: return TypeKind::STRUCT;
    default: return TypeKind::INVALID_TYPE_KIND;
  }
}

/**
 * @brief Translates time unit to nanoscale multiple.
 */
constexpr int32_t to_clockscale(cudf::type_id timestamp_id)
{
  switch (timestamp_id) {
    case cudf::type_id::TIMESTAMP_SECONDS: return 9;
    case cudf::type_id::TIMESTAMP_MILLISECONDS: return 6;
    case cudf::type_id::TIMESTAMP_MICROSECONDS: return 3;
    case cudf::type_id::TIMESTAMP_NANOSECONDS:
    default: return 0;
  }
}

/**
 * @brief Returns the precision of the given decimal type.
 */
constexpr auto orc_precision(cudf::type_id decimal_id)
{
  switch (decimal_id) {
    case cudf::type_id::DECIMAL32: return 9;
    case cudf::type_id::DECIMAL64: return 18;
    default: return 0;
  }
}

}  // namespace

/**
 * @brief Helper class that adds ORC-specific column info
 */
class orc_column_view {
 public:
  /**
   * @brief Constructor that extracts out the string position + length pairs
   * for building dictionaries for string columns
   */
  explicit orc_column_view(uint32_t index,
                           int str_idx,
                           orc_column_view* parent,
                           column_view const& col,
                           column_in_metadata const& metadata)
    : cudf_column{col},
      _index{index},
      _str_idx{str_idx},
      _is_child{parent != nullptr},
      _type_width{cudf::is_fixed_width(col.type()) ? cudf::size_of(col.type()) : 0},
      _scale{(to_orc_type(col.type().id()) == TypeKind::DECIMAL) ? -col.type().scale()
                                                                 : to_clockscale(col.type().id())},
      _precision{metadata.is_decimal_precision_set() ? metadata.get_decimal_precision()
                                                     : orc_precision(col.type().id())},
      _type_kind{to_orc_type(col.type().id())},
      name{metadata.get_name()}
  {
    if (metadata.is_nullability_defined()) { nullable_from_metadata = metadata.nullable(); }
    if (parent != nullptr) {
      parent->add_child(_index);
      _parent_index = parent->index();
    }
  }

  void add_child(uint32_t child_idx) { children.emplace_back(child_idx); }

  auto is_string() const noexcept { return cudf_column.type().id() == type_id::STRING; }
  void set_dict_stride(size_t stride) noexcept { _dict_stride = stride; }
  auto dict_stride() const noexcept { return _dict_stride; }

  /**
   * @brief Function that associates an existing dictionary chunk allocation
   */
  void attach_dict_chunk(gpu::DictionaryChunk const* host_dict,
                         gpu::DictionaryChunk const* dev_dict)
  {
    dict   = host_dict;
    d_dict = dev_dict;
  }
  auto host_dict_chunk(size_t rowgroup) const
  {
    CUDF_EXPECTS(is_string(), "Dictionary chunks are only present in string columns.");
    return &dict[rowgroup * _dict_stride + _str_idx];
  }
  auto device_dict_chunk() const { return d_dict; }

  auto const& decimal_offsets() const { return d_decimal_offsets; }
  void attach_decimal_offsets(uint32_t* sizes_ptr) { d_decimal_offsets = sizes_ptr; }

  /**
   * @brief Function that associates an existing stripe dictionary allocation
   */
  void attach_stripe_dict(gpu::StripeDictionary* host_stripe_dict,
                          gpu::StripeDictionary* dev_stripe_dict)
  {
    stripe_dict   = host_stripe_dict;
    d_stripe_dict = dev_stripe_dict;
  }
  auto host_stripe_dict(size_t stripe) const
  {
    CUDF_EXPECTS(is_string(), "Stripe dictionary is only present in string columns.");
    return &stripe_dict[stripe * _dict_stride + _str_idx];
  }
  auto device_stripe_dict() const noexcept { return d_stripe_dict; }

  // Index in the table
  uint32_t index() const noexcept { return _index; }
  // Id in the ORC file
  auto id() const noexcept { return _index + 1; }

  auto is_child() const noexcept { return _is_child; }
  auto parent_index() const noexcept { return _parent_index.value(); }
  auto child_begin() const noexcept { return children.cbegin(); }
  auto child_end() const noexcept { return children.cend(); }

  auto type_width() const noexcept { return _type_width; }
  auto size() const noexcept { return cudf_column.size(); }

  auto null_count() const noexcept { return cudf_column.null_count(); }
  auto null_mask() const noexcept { return cudf_column.null_mask(); }
  bool nullable() const noexcept { return null_mask() != nullptr; }
  auto user_defined_nullable() const noexcept { return nullable_from_metadata; }

  auto scale() const noexcept { return _scale; }
  auto precision() const noexcept { return _precision; }

  void set_orc_encoding(ColumnEncodingKind e) noexcept { _encoding_kind = e; }
  auto orc_kind() const noexcept { return _type_kind; }
  auto orc_encoding() const noexcept { return _encoding_kind; }
  std::string_view orc_name() const noexcept { return name; }

 private:
  column_view cudf_column;

  // Identifier within the set of columns
  uint32_t _index = 0;
  // Identifier within the set of string columns
  int _str_idx;
  bool _is_child = false;

  size_t _type_width = 0;
  int32_t _scale     = 0;
  int32_t _precision = 0;

  // ORC-related members
  TypeKind _type_kind               = INVALID_TYPE_KIND;
  ColumnEncodingKind _encoding_kind = INVALID_ENCODING_KIND;
  std::string name;

  // String dictionary-related members
  size_t _dict_stride                        = 0;
  gpu::DictionaryChunk const* dict           = nullptr;
  gpu::StripeDictionary const* stripe_dict   = nullptr;
  gpu::DictionaryChunk const* d_dict         = nullptr;
  gpu::StripeDictionary const* d_stripe_dict = nullptr;

  // Offsets for encoded decimal elements. Used to enable direct writing of encoded decimal elements
  // into the output stream.
  uint32_t* d_decimal_offsets = nullptr;

  std::optional<bool> nullable_from_metadata;
  std::vector<uint32_t> children;
  std::optional<uint32_t> _parent_index;
};

size_type orc_table_view::num_rows() const noexcept
{
  return columns.empty() ? 0 : columns.front().size();
}

/**
 * @brief Gathers stripe information.
 *
 * @param columns List of columns
 * @param rowgroup_bounds Ranges of rows in each rowgroup [rowgroup][column]
 * @param max_stripe_bytes Maximum size of each stripe, in bytes
 * @return List of stripe descriptors
 */
file_segmentation calculate_segmentation(host_span<orc_column_view const> columns,
                                         hostdevice_2dvector<rowgroup_rows>&& rowgroup_bounds,
                                         uint32_t max_stripe_bytes)
{
  auto const is_any_column_string =
    std::any_of(columns.begin(), columns.end(), [](auto const& col) { return col.is_string(); });
  // Apply rows per stripe limit to limit string dictionaries
  size_t const max_stripe_rows = is_any_column_string ? 1000000 : 5000000;

  std::vector<stripe_rowgroups> infos;
  auto const num_rowgroups = rowgroup_bounds.size().first;
  size_t stripe_start      = 0;
  size_t stripe_bytes      = 0;
  size_t stripe_rows       = 0;
  for (size_t rg_idx = 0; rg_idx < num_rowgroups; ++rg_idx) {
    auto const rowgroup_total_bytes =
      std::accumulate(columns.begin(), columns.end(), 0ul, [&](size_t total_size, auto const& col) {
        auto const rows = rowgroup_bounds[rg_idx][col.index()].size();
        if (col.is_string()) {
          const auto dt = col.host_dict_chunk(rg_idx);
          return total_size + rows + dt->string_char_count;
        } else {
          return total_size + col.type_width() * rows;
        }
      });

    auto const rowgroup_rows_max =
      std::max_element(rowgroup_bounds[rg_idx].begin(),
                       rowgroup_bounds[rg_idx].end(),
                       [](auto& l, auto& r) { return l.size() < r.size(); })
        ->size();
    // Check if adding the current rowgroup to the stripe will make the stripe too large or long
    if ((rg_idx > stripe_start) && (stripe_bytes + rowgroup_total_bytes > max_stripe_bytes ||
                                    stripe_rows + rowgroup_rows_max > max_stripe_rows)) {
      infos.emplace_back(infos.size(), stripe_start, rg_idx - stripe_start);
      stripe_start = rg_idx;
      stripe_bytes = 0;
      stripe_rows  = 0;
    }

    stripe_bytes += rowgroup_total_bytes;
    stripe_rows += rowgroup_rows_max;
    if (rg_idx + 1 == num_rowgroups) {
      infos.emplace_back(infos.size(), stripe_start, num_rowgroups - stripe_start);
    }
  }

  return {std::move(rowgroup_bounds), std::move(infos)};
}

/**
 * @brief Builds up column dictionaries indices
 *
 * @param orc_table Non-owning view of a cuDF table w/ ORC-related info
 * @param rowgroup_bounds Ranges of rows in each rowgroup [rowgroup][column]
 * @param dict_data Dictionary data memory
 * @param dict_index Dictionary index memory
 * @param dict List of dictionary chunks
 * @param stream CUDA stream used for device memory operations and kernel launches
 */
void init_dictionaries(orc_table_view& orc_table,
                       device_2dspan<rowgroup_rows const> rowgroup_bounds,
                       device_span<device_span<uint32_t>> dict_data,
                       device_span<device_span<uint32_t>> dict_index,
                       hostdevice_2dvector<gpu::DictionaryChunk>* dict,
                       rmm::cuda_stream_view stream)
{
  // Setup per-rowgroup dictionary indexes for each dictionary-aware column
  for (auto col_idx : orc_table.string_column_indices) {
    auto& str_column = orc_table.column(col_idx);
    str_column.set_dict_stride(orc_table.num_string_columns());
    str_column.attach_dict_chunk(dict->base_host_ptr(), dict->base_device_ptr());
  }

  // Allocate temporary memory for dictionary indices
  std::vector<rmm::device_uvector<uint32_t>> dict_indices;
  dict_indices.reserve(orc_table.num_string_columns());
  std::transform(orc_table.string_column_indices.cbegin(),
                 orc_table.string_column_indices.cend(),
                 std::back_inserter(dict_indices),
                 [&](auto& col_idx) {
                   auto& str_column = orc_table.column(col_idx);
                   return cudf::detail::make_zeroed_device_uvector_async<uint32_t>(
                     str_column.size(), stream);
                 });

  // Create views of the temporary buffers in device memory
  std::vector<device_span<uint32_t>> dict_indices_views;
  dict_indices_views.reserve(dict_indices.size());
  std::transform(
    dict_indices.begin(), dict_indices.end(), std::back_inserter(dict_indices_views), [](auto& di) {
      return device_span<uint32_t>{di};
    });
  auto d_dict_indices_views = cudf::detail::make_device_uvector_async(dict_indices_views, stream);

  gpu::InitDictionaryIndices(orc_table.d_columns,
                             *dict,
                             dict_data,
                             dict_index,
                             d_dict_indices_views,
                             rowgroup_bounds,
                             orc_table.d_string_column_indices,
                             stream);
  dict->device_to_host(stream, true);
}

void writer::impl::build_dictionaries(orc_table_view& orc_table,
                                      host_span<stripe_rowgroups const> stripe_bounds,
                                      hostdevice_2dvector<gpu::DictionaryChunk> const& dict,
                                      host_span<rmm::device_uvector<uint32_t>> dict_index,
                                      host_span<bool const> dictionary_enabled,
                                      hostdevice_2dvector<gpu::StripeDictionary>& stripe_dict)
{
  const auto num_rowgroups = dict.size().first;

  for (size_t dict_idx = 0; dict_idx < orc_table.num_string_columns(); ++dict_idx) {
    auto& str_column = orc_table.string_column(dict_idx);
    str_column.attach_stripe_dict(stripe_dict.base_host_ptr(), stripe_dict.base_device_ptr());

    for (auto const& stripe : stripe_bounds) {
      auto& sd           = stripe_dict[stripe.id][dict_idx];
      sd.dict_data       = str_column.host_dict_chunk(stripe.first)->dict_data;
      sd.dict_index      = dict_index[dict_idx].data();  // Indexed by abs row
      sd.column_id       = orc_table.string_column_indices[dict_idx];
      sd.start_chunk     = stripe.first;
      sd.num_chunks      = stripe.size;
      sd.dict_char_count = 0;
      sd.num_strings =
        std::accumulate(stripe.cbegin(), stripe.cend(), 0, [&](auto dt_str_cnt, auto rg_idx) {
          const auto& dt = dict[rg_idx][dict_idx];
          return dt_str_cnt + dt.num_dict_strings;
        });
      sd.leaf_column = dict[0][dict_idx].leaf_column;
    }

    if (enable_dictionary_) {
      struct string_column_cost {
        size_t direct     = 0;
        size_t dictionary = 0;
      };
      auto const col_cost =
        std::accumulate(stripe_bounds.front().cbegin(),
                        stripe_bounds.back().cend(),
                        string_column_cost{},
                        [&](auto cost, auto rg_idx) -> string_column_cost {
                          const auto& dt = dict[rg_idx][dict_idx];
                          return {cost.direct + dt.string_char_count,
                                  cost.dictionary + dt.dict_char_count + dt.num_dict_strings};
                        });
      // Disable dictionary if it does not reduce the output size
      if (!dictionary_enabled[orc_table.string_column(dict_idx).index()] ||
          col_cost.dictionary >= col_cost.direct) {
        for (auto const& stripe : stripe_bounds) {
          stripe_dict[stripe.id][dict_idx].dict_data = nullptr;
        }
      }
    }
  }

  stripe_dict.host_to_device(stream);
  gpu::BuildStripeDictionaries(stripe_dict, stripe_dict, dict, stream);
  stripe_dict.device_to_host(stream, true);
}

constexpr size_t RLE_stream_size(TypeKind kind, size_t count)
{
  using cudf::util::div_rounding_up_unsafe;
  constexpr auto byte_rle_max_len = 128;
  switch (kind) {
    case TypeKind::BOOLEAN:
      return div_rounding_up_unsafe(count, byte_rle_max_len * 8) * (byte_rle_max_len + 1);
    case TypeKind::BYTE:
      return div_rounding_up_unsafe(count, byte_rle_max_len) * (byte_rle_max_len + 1);
    case TypeKind::SHORT:
      return div_rounding_up_unsafe(count, gpu::encode_block_size) *
             (gpu::encode_block_size * sizeof(int16_t) + 2);
    case TypeKind::FLOAT:
    case TypeKind::INT:
    case TypeKind::DATE:
      return div_rounding_up_unsafe(count, gpu::encode_block_size) *
             (gpu::encode_block_size * sizeof(int32_t) + 2);
    case TypeKind::LONG:
    case TypeKind::DOUBLE:
      return div_rounding_up_unsafe(count, gpu::encode_block_size) *
             (gpu::encode_block_size * sizeof(int64_t) + 2);
    default: CUDF_FAIL("Unsupported ORC type for RLE stream size");
  }
}

orc_streams writer::impl::create_streams(host_span<orc_column_view> columns,
                                         file_segmentation const& segmentation,
                                         std::map<uint32_t, size_t> const& decimal_column_sizes)
{
  // 'column 0' row index stream
  std::vector<Stream> streams{{ROW_INDEX, 0}};  // TODO: Separate index and data streams?
  // First n + 1 streams are row index streams
  streams.reserve(columns.size() + 1);
  std::transform(columns.begin(), columns.end(), std::back_inserter(streams), [](auto const& col) {
    return Stream{ROW_INDEX, col.id()};
  });

  std::vector<int32_t> ids(columns.size() * gpu::CI_NUM_STREAMS, -1);
  std::vector<TypeKind> types(streams.size(), INVALID_TYPE_KIND);

  for (auto& column : columns) {
    auto const is_nullable = [&]() -> bool {
      if (single_write_mode) {
        return column.nullable();
      } else {
        // For chunked write, when not provided nullability, we assume the worst case scenario
        // that all columns are nullable.
        auto const chunked_nullable = column.user_defined_nullable().value_or(true);
        CUDF_EXPECTS(chunked_nullable or !column.nullable(),
                     "Mismatch in metadata prescribed nullability and input column nullability. "
                     "Metadata for nullable input column cannot prescribe nullability = false");
        return chunked_nullable;
      }
    }();

    auto RLE_column_size = [&](TypeKind type_kind) {
      return std::accumulate(
        thrust::make_counting_iterator(0ul),
        thrust::make_counting_iterator(segmentation.num_rowgroups()),
        0ul,
        [&](auto data_size, auto rg_idx) {
          return data_size +
                 RLE_stream_size(type_kind, segmentation.rowgroups[rg_idx][column.index()].size());
        });
    };

    auto const kind = column.orc_kind();

    auto add_stream =
      [&](gpu::StreamIndexType index_type, StreamKind kind, TypeKind type_kind, size_t size) {
        const auto base        = column.index() * gpu::CI_NUM_STREAMS;
        ids[base + index_type] = streams.size();
        streams.push_back(orc::Stream{kind, column.id(), size});
        types.push_back(type_kind);
      };

    auto add_RLE_stream = [&](
                            gpu::StreamIndexType index_type, StreamKind kind, TypeKind type_kind) {
      add_stream(index_type, kind, type_kind, RLE_column_size(type_kind));
    };

    if (is_nullable) { add_RLE_stream(gpu::CI_PRESENT, PRESENT, TypeKind::BOOLEAN); }
    switch (kind) {
      case TypeKind::BOOLEAN:
      case TypeKind::BYTE:
        add_RLE_stream(gpu::CI_DATA, DATA, kind);
        column.set_orc_encoding(DIRECT);
        break;
      case TypeKind::SHORT:
      case TypeKind::INT:
      case TypeKind::LONG:
      case TypeKind::DATE:
        add_RLE_stream(gpu::CI_DATA, DATA, kind);
        column.set_orc_encoding(DIRECT_V2);
        break;
      case TypeKind::FLOAT:
      case TypeKind::DOUBLE:
        // Pass through if no nulls (no RLE encoding for floating point)
        add_stream(
          gpu::CI_DATA, DATA, kind, (column.null_count() != 0) ? RLE_column_size(kind) : 0);
        column.set_orc_encoding(DIRECT);
        break;
      case TypeKind::STRING: {
        bool enable_dict           = enable_dictionary_;
        size_t dict_data_size      = 0;
        size_t dict_strings        = 0;
        size_t dict_lengths_div512 = 0;
        for (auto const& stripe : segmentation.stripes) {
          const auto sd = column.host_stripe_dict(stripe.id);
          enable_dict   = (enable_dict && sd->dict_data != nullptr);
          if (enable_dict) {
            dict_strings += sd->num_strings;
            dict_lengths_div512 += (sd->num_strings + 0x1ff) >> 9;
            dict_data_size += sd->dict_char_count;
          }
        }

        auto const direct_data_size =
          std::accumulate(segmentation.stripes.front().cbegin(),
                          segmentation.stripes.back().cend(),
                          size_t{0},
                          [&](auto data_size, auto rg_idx) {
                            return data_size + column.host_dict_chunk(rg_idx)->string_char_count;
                          });
        if (enable_dict) {
          uint32_t dict_bits = 0;
          for (dict_bits = 1; dict_bits < 32; dict_bits <<= 1) {
            if (dict_strings <= (1ull << dict_bits)) break;
          }
          const auto valid_count = column.size() - column.null_count();
          dict_data_size += (dict_bits * valid_count + 7) >> 3;
        }

        // Decide between direct or dictionary encoding
        if (enable_dict && dict_data_size < direct_data_size) {
          add_RLE_stream(gpu::CI_DATA, DATA, TypeKind::INT);
          add_stream(gpu::CI_DATA2, LENGTH, TypeKind::INT, dict_lengths_div512 * (512 * 4 + 2));
          add_stream(
            gpu::CI_DICTIONARY, DICTIONARY_DATA, TypeKind::CHAR, std::max(dict_data_size, 1ul));
          column.set_orc_encoding(DICTIONARY_V2);
        } else {
          add_stream(gpu::CI_DATA, DATA, TypeKind::CHAR, std::max<size_t>(direct_data_size, 1));
          add_RLE_stream(gpu::CI_DATA2, LENGTH, TypeKind::INT);
          column.set_orc_encoding(DIRECT_V2);
        }
        break;
      }
      case TypeKind::TIMESTAMP:
        add_RLE_stream(gpu::CI_DATA, DATA, TypeKind::LONG);
        add_RLE_stream(gpu::CI_DATA2, SECONDARY, TypeKind::LONG);
        column.set_orc_encoding(DIRECT_V2);
        break;
      case TypeKind::DECIMAL:
        // varint values (NO RLE)
        // data_stream_size = decimal_column_sizes.at(column.index());
        add_stream(gpu::CI_DATA, DATA, TypeKind::DECIMAL, decimal_column_sizes.at(column.index()));
        // scale stream TODO: compute exact size since all elems are equal
        add_RLE_stream(gpu::CI_DATA2, SECONDARY, TypeKind::INT);
        column.set_orc_encoding(DIRECT_V2);
        break;
      case TypeKind::LIST:
        // no data stream, only lengths
        add_RLE_stream(gpu::CI_DATA2, LENGTH, TypeKind::INT);
        column.set_orc_encoding(DIRECT_V2);
        break;
      case TypeKind::STRUCT:
        // Only has the present stream
        break;
      default: CUDF_FAIL("Unsupported ORC type kind");
    }
  }
  return {std::move(streams), std::move(ids), std::move(types)};
}

orc_streams::orc_stream_offsets orc_streams::compute_offsets(
  host_span<orc_column_view const> columns, size_t num_rowgroups) const
{
  std::vector<size_t> strm_offsets(streams.size());
  size_t non_rle_data_size = 0;
  size_t rle_data_size     = 0;
  for (size_t i = 0; i < streams.size(); ++i) {
    const auto& stream = streams[i];

    auto const is_rle_data = [&]() {
      // First stream is an index stream, don't check types, etc.
      if (!stream.column_index().has_value()) return true;

      auto const& column = columns[stream.column_index().value()];
      // Dictionary encoded string column - dictionary characters or
      // directly encoded string - column characters
      if (column.orc_kind() == TypeKind::STRING &&
          ((stream.kind == DICTIONARY_DATA && column.orc_encoding() == DICTIONARY_V2) ||
           (stream.kind == DATA && column.orc_encoding() == DIRECT_V2)))
        return false;
      // Decimal data
      if (column.orc_kind() == TypeKind::DECIMAL && stream.kind == DATA) return false;

      // Everything else uses RLE
      return true;
    }();
    // non-RLE and RLE streams are separated in the buffer that stores encoded data
    // The computed offsets do not take the streams of the other type into account
    if (is_rle_data) {
      strm_offsets[i] = rle_data_size;
      rle_data_size += (stream.length + 7) & ~7;
    } else {
      strm_offsets[i] = non_rle_data_size;
      non_rle_data_size += stream.length;
    }
  }
  non_rle_data_size = (non_rle_data_size + 7) & ~7;

  return {std::move(strm_offsets), non_rle_data_size, rle_data_size};
}

std::vector<std::vector<rowgroup_rows>> calculate_aligned_rowgroup_bounds(
  orc_table_view const& orc_table,
  file_segmentation const& segmentation,
  rmm::cuda_stream_view stream)
{
  if (segmentation.num_rowgroups() == 0) return {};

  auto d_pd_set_counts_data = rmm::device_uvector<cudf::size_type>(
    orc_table.num_columns() * segmentation.num_rowgroups(), stream);
  auto const d_pd_set_counts = device_2dspan<cudf::size_type>{
    d_pd_set_counts_data.data(), segmentation.num_rowgroups(), orc_table.num_columns()};
  gpu::reduce_pushdown_masks(orc_table.d_columns, segmentation.rowgroups, d_pd_set_counts, stream);

  auto aligned_rgs = hostdevice_2dvector<rowgroup_rows>(
    segmentation.num_rowgroups(), orc_table.num_columns(), stream);
  CUDA_TRY(cudaMemcpyAsync(aligned_rgs.base_device_ptr(),
                           segmentation.rowgroups.base_device_ptr(),
                           aligned_rgs.count() * sizeof(rowgroup_rows),
                           cudaMemcpyDefault,
                           stream.value()));
  auto const d_stripes = cudf::detail::make_device_uvector_async(segmentation.stripes, stream);

  // One thread per column, per stripe
  thrust::for_each_n(
    rmm::exec_policy(stream),
    thrust::make_counting_iterator(0),
    orc_table.num_columns() * segmentation.num_stripes(),
    [columns = device_span<orc_column_device_view const>{orc_table.d_columns},
     stripes = device_span<stripe_rowgroups const>{d_stripes},
     d_pd_set_counts,
     out_rowgroups = device_2dspan<rowgroup_rows>{aligned_rgs}] __device__(auto& idx) {
      uint32_t const col_idx = idx / stripes.size();
      // No alignment needed for root columns
      if (not columns[col_idx].parent_index.has_value()) return;

      auto const stripe_idx     = idx % stripes.size();
      auto const stripe         = stripes[stripe_idx];
      auto const parent_col_idx = columns[col_idx].parent_index.value();
      auto const parent_column  = columns[parent_col_idx];
      auto const stripe_end     = stripe.first + stripe.size;

      auto seek_last_borrow_rg = [&](auto rg_idx, size_type& bits_to_borrow) {
        auto curr         = rg_idx + 1;
        auto curr_rg_size = [&]() {
          return parent_column.pushdown_mask != nullptr ? d_pd_set_counts[curr][parent_col_idx]
                                                        : out_rowgroups[curr][col_idx].size();
        };
        while (curr < stripe_end and curr_rg_size() <= bits_to_borrow) {
          // All bits from rowgroup borrowed, make the rowgroup empty
          out_rowgroups[curr][col_idx].begin = out_rowgroups[curr][col_idx].end;
          bits_to_borrow -= curr_rg_size();
          ++curr;
        }
        return curr;
      };

      int previously_borrowed = 0;
      for (auto rg_idx = stripe.first; rg_idx + 1 < stripe_end; ++rg_idx) {
        auto& rg = out_rowgroups[rg_idx][col_idx];

        if (parent_column.pushdown_mask == nullptr) {
          // No pushdown mask, all null mask bits will be encoded
          // Align on rowgroup size (can be misaligned for list children)
          if (rg.size() % 8) {
            auto bits_to_borrow           = 8 - rg.size() % 8;
            auto const last_borrow_rg_idx = seek_last_borrow_rg(rg_idx, bits_to_borrow);
            if (last_borrow_rg_idx == stripe_end) {
              // Didn't find enough bits to borrow, move the rowgroup end to the stripe end
              rg.end = out_rowgroups[stripe_end - 1][col_idx].end;
              // Done with this stripe
              break;
            }
            auto& last_borrow_rg = out_rowgroups[last_borrow_rg_idx][col_idx];
            last_borrow_rg.begin += bits_to_borrow;
            rg.end = last_borrow_rg.begin;
            // Skip the rowgroups we emptied in the loop
            rg_idx = last_borrow_rg_idx - 1;
          }
        } else {
          // pushdown mask present; null mask bits w/ set pushdown mask bits will be encoded
          // Use the number of set bits in pushdown mask as size
          auto bits_to_borrow =
            8 - (d_pd_set_counts[rg_idx][parent_col_idx] - previously_borrowed) % 8;
          if (bits_to_borrow == 0) {
            // Didn't borrow any bits for this rowgroup
            previously_borrowed = 0;
            continue;
          }

          // Find rowgroup in which we finish the search for missing bits
          auto const last_borrow_rg_idx = seek_last_borrow_rg(rg_idx, bits_to_borrow);
          if (last_borrow_rg_idx == stripe_end) {
            // Didn't find enough bits to borrow, move the rowgroup end to the stripe end
            rg.end = out_rowgroups[stripe_end - 1][col_idx].end;
            // Done with this stripe
            break;
          }

          auto& last_borrow_rg = out_rowgroups[last_borrow_rg_idx][col_idx];
          // First row that does not need to be borrowed
          auto borrow_end = last_borrow_rg.begin;

          // Adjust the number of bits to borrow in the next iteration
          previously_borrowed = bits_to_borrow;

          // Find word in which we finish the search for missing bits (guaranteed to be available)
          while (bits_to_borrow != 0) {
            auto const mask = cudf::detail::get_mask_offset_word(
              parent_column.pushdown_mask, 0, borrow_end, borrow_end + 32);
            auto const valid_in_word = __popc(mask);

            if (valid_in_word > bits_to_borrow) break;
            bits_to_borrow -= valid_in_word;
            borrow_end += 32;
          }

          // Find the last of the missing bits (guaranteed to be available)
          while (bits_to_borrow != 0) {
            if (bit_is_set(parent_column.pushdown_mask, borrow_end)) { --bits_to_borrow; };
            ++borrow_end;
          }

          last_borrow_rg.begin = borrow_end;
          rg.end               = borrow_end;
          // Skip the rowgroups we emptied in the loop
          rg_idx = last_borrow_rg_idx - 1;
        }
      }
    });

  aligned_rgs.device_to_host(stream, true);

  std::vector<std::vector<rowgroup_rows>> h_aligned_rgs;
  h_aligned_rgs.reserve(segmentation.num_rowgroups());
  std::transform(thrust::make_counting_iterator(0ul),
                 thrust::make_counting_iterator(segmentation.num_rowgroups()),
                 std::back_inserter(h_aligned_rgs),
                 [&](auto idx) -> std::vector<rowgroup_rows> {
                   return {aligned_rgs[idx].begin(), aligned_rgs[idx].end()};
                 });

  return h_aligned_rgs;
}

struct segmented_valid_cnt_input {
  bitmask_type const* mask;
  std::vector<size_type> indices;
};

encoded_data encode_columns(orc_table_view const& orc_table,
                            string_dictionaries&& dictionaries,
                            encoder_decimal_info&& dec_chunk_sizes,
                            file_segmentation const& segmentation,
                            orc_streams const& streams,
                            rmm::cuda_stream_view stream)
{
  auto const num_columns = orc_table.num_columns();
  hostdevice_2dvector<gpu::EncChunk> chunks(num_columns, segmentation.num_rowgroups(), stream);
  auto const stream_offsets =
    streams.compute_offsets(orc_table.columns, segmentation.num_rowgroups());
  rmm::device_uvector<uint8_t> encoded_data(stream_offsets.data_size(), stream);

  auto const aligned_rowgroups = calculate_aligned_rowgroup_bounds(orc_table, segmentation, stream);

  // Initialize column chunks' descriptions
  std::map<size_type, segmented_valid_cnt_input> validity_check_inputs;

  for (auto const& column : orc_table.columns) {
    for (auto const& stripe : segmentation.stripes) {
      for (auto rg_idx_it = stripe.cbegin(); rg_idx_it < stripe.cend(); ++rg_idx_it) {
        auto const rg_idx      = *rg_idx_it;
        auto& ck               = chunks[column.index()][rg_idx];
        ck.start_row           = segmentation.rowgroups[rg_idx][column.index()].begin;
        ck.num_rows            = segmentation.rowgroups[rg_idx][column.index()].size();
        ck.null_mask_start_row = aligned_rowgroups[rg_idx][column.index()].begin;
        ck.null_mask_num_rows  = aligned_rowgroups[rg_idx][column.index()].size();
        ck.encoding_kind       = column.orc_encoding();
        ck.type_kind           = column.orc_kind();
        if (ck.type_kind == TypeKind::STRING) {
          ck.dict_index = (ck.encoding_kind == DICTIONARY_V2)
                            ? column.host_stripe_dict(stripe.id)->dict_index
                            : nullptr;
          ck.dtype_len  = 1;
        } else {
          ck.dtype_len = column.type_width();
        }
        ck.scale = column.scale();
        if (ck.type_kind == TypeKind::DECIMAL) { ck.decimal_offsets = column.decimal_offsets(); }
      }
    }
  }
  chunks.host_to_device(stream);
  // TODO (future): pass columns separately from chunks (to skip this step)
  // and remove info from chunks that is common for the entire column
  thrust::for_each_n(
    rmm::exec_policy(stream),
    thrust::make_counting_iterator(0ul),
    chunks.count(),
    [chunks = device_2dspan<gpu::EncChunk>{chunks},
     cols = device_span<orc_column_device_view const>{orc_table.d_columns}] __device__(auto& idx) {
      auto const col_idx             = idx / chunks.size().second;
      auto const rg_idx              = idx % chunks.size().second;
      chunks[col_idx][rg_idx].column = &cols[col_idx];
    });

  auto validity_check_indices = [&](size_t col_idx) {
    std::vector<size_type> indices;
    for (auto const& stripe : segmentation.stripes) {
      for (auto rg_idx_it = stripe.cbegin(); rg_idx_it < stripe.cend() - 1; ++rg_idx_it) {
        auto const& chunk = chunks[col_idx][*rg_idx_it];
        indices.push_back(chunk.start_row);
        indices.push_back(chunk.start_row + chunk.num_rows);
      }
    }
    return indices;
  };
  for (auto const& column : orc_table.columns) {
    if (column.orc_kind() == TypeKind::BOOLEAN && column.nullable()) {
      validity_check_inputs[column.index()] = {column.null_mask(),
                                               validity_check_indices(column.index())};
    }
  }
  for (auto& cnt_in : validity_check_inputs) {
    auto const valid_counts = segmented_count_set_bits(cnt_in.second.mask, cnt_in.second.indices);
    CUDF_EXPECTS(
      std::none_of(valid_counts.cbegin(),
                   valid_counts.cend(),
                   [](auto valid_count) { return valid_count % 8; }),
      "There's currently a bug in encoding boolean columns. Suggested workaround is to convert "
      "to int8 type."
      " Please see https://github.com/rapidsai/cudf/issues/6763 for more information.");
  }

  hostdevice_2dvector<gpu::encoder_chunk_streams> chunk_streams(
    num_columns, segmentation.num_rowgroups(), stream);
  for (size_t col_idx = 0; col_idx < num_columns; col_idx++) {
    auto const& column = orc_table.column(col_idx);
    auto col_streams   = chunk_streams[col_idx];
    for (auto const& stripe : segmentation.stripes) {
      for (auto rg_idx_it = stripe.cbegin(); rg_idx_it < stripe.cend(); ++rg_idx_it) {
        auto const rg_idx = *rg_idx_it;
        auto const& ck    = chunks[col_idx][rg_idx];
        auto& strm        = col_streams[rg_idx];

        for (int strm_type = 0; strm_type < gpu::CI_NUM_STREAMS; ++strm_type) {
          auto const strm_id = streams.id(col_idx * gpu::CI_NUM_STREAMS + strm_type);

          strm.ids[strm_type] = strm_id;
          if (strm_id >= 0) {
            if ((strm_type == gpu::CI_DICTIONARY) ||
                (strm_type == gpu::CI_DATA2 && ck.encoding_kind == DICTIONARY_V2)) {
              if (rg_idx_it == stripe.cbegin()) {
                const int32_t dict_stride = column.dict_stride();
                const auto stripe_dict    = column.host_stripe_dict(stripe.id);
                strm.lengths[strm_type] =
                  (strm_type == gpu::CI_DICTIONARY)
                    ? stripe_dict->dict_char_count
                    : (((stripe_dict->num_strings + 0x1ff) >> 9) * (512 * 4 + 2));
                if (stripe.id == 0) {
                  strm.data_ptrs[strm_type] = encoded_data.data() + stream_offsets.offsets[strm_id];
                  // Dictionary lengths are encoded as RLE, which are all stored after non-RLE data:
                  // include non-RLE data size in the offset only in that case
                  if (strm_type == gpu::CI_DATA2 && ck.encoding_kind == DICTIONARY_V2)
                    strm.data_ptrs[strm_type] += stream_offsets.non_rle_data_size;
                } else {
                  auto const& strm_up = col_streams[stripe_dict[-dict_stride].start_chunk];
                  strm.data_ptrs[strm_type] =
                    strm_up.data_ptrs[strm_type] + strm_up.lengths[strm_type];
                }
              } else {
                strm.lengths[strm_type]   = 0;
                strm.data_ptrs[strm_type] = col_streams[rg_idx - 1].data_ptrs[strm_type];
              }
            } else if (strm_type == gpu::CI_DATA && ck.type_kind == TypeKind::STRING &&
                       ck.encoding_kind == DIRECT_V2) {
              strm.lengths[strm_type]   = column.host_dict_chunk(rg_idx)->string_char_count;
              strm.data_ptrs[strm_type] = (rg_idx == 0)
                                            ? encoded_data.data() + stream_offsets.offsets[strm_id]
                                            : (col_streams[rg_idx - 1].data_ptrs[strm_type] +
                                               col_streams[rg_idx - 1].lengths[strm_type]);
            } else if (strm_type == gpu::CI_DATA && streams[strm_id].length == 0 &&
                       (ck.type_kind == DOUBLE || ck.type_kind == FLOAT)) {
              // Pass-through
              strm.lengths[strm_type]   = ck.num_rows * ck.dtype_len;
              strm.data_ptrs[strm_type] = nullptr;

            } else if (ck.type_kind == DECIMAL && strm_type == gpu::CI_DATA) {
              strm.lengths[strm_type]   = dec_chunk_sizes.rg_sizes.at(col_idx)[rg_idx];
              strm.data_ptrs[strm_type] = (rg_idx == 0)
                                            ? encoded_data.data() + stream_offsets.offsets[strm_id]
                                            : (col_streams[rg_idx - 1].data_ptrs[strm_type] +
                                               col_streams[rg_idx - 1].lengths[strm_type]);
            } else {
              strm.lengths[strm_type] = RLE_stream_size(streams.type(strm_id), ck.num_rows);
              // RLE encoded streams are stored after all non-RLE streams
              strm.data_ptrs[strm_type] =
                (rg_idx == 0) ? (encoded_data.data() + stream_offsets.non_rle_data_size +
                                 stream_offsets.offsets[strm_id])
                              : (col_streams[rg_idx - 1].data_ptrs[strm_type] +
                                 col_streams[rg_idx - 1].lengths[strm_type]);
            }
          } else {
            strm.lengths[strm_type]   = 0;
            strm.data_ptrs[strm_type] = nullptr;
          }
        }
      }
    }
  }
  chunk_streams.host_to_device(stream);

  if (orc_table.num_string_columns() != 0) {
    auto d_stripe_dict = orc_table.string_column(0).device_stripe_dict();
    gpu::EncodeStripeDictionaries(d_stripe_dict,
                                  chunks,
                                  orc_table.num_string_columns(),
                                  segmentation.num_stripes(),
                                  chunk_streams,
                                  stream);
  }

  gpu::EncodeOrcColumnData(chunks, chunk_streams, stream);
  dictionaries.data.clear();
  dictionaries.index.clear();
  stream.synchronize();

  return {std::move(encoded_data), std::move(chunk_streams)};
}

std::vector<StripeInformation> writer::impl::gather_stripes(
  size_t num_index_streams,
  file_segmentation const& segmentation,
  hostdevice_2dvector<gpu::encoder_chunk_streams>* enc_streams,
  hostdevice_2dvector<gpu::StripeStream>* strm_desc)
{
  std::vector<StripeInformation> stripes(segmentation.num_stripes());
  for (auto const& stripe : segmentation.stripes) {
    for (size_t col_idx = 0; col_idx < enc_streams->size().first; col_idx++) {
      const auto& strm = (*enc_streams)[col_idx][stripe.first];

      // Assign stream data of column data stream(s)
      for (int k = 0; k < gpu::CI_INDEX; k++) {
        const auto stream_id = strm.ids[k];
        if (stream_id != -1) {
          auto* ss           = &(*strm_desc)[stripe.id][stream_id - num_index_streams];
          ss->stream_size    = 0;
          ss->first_chunk_id = stripe.first;
          ss->num_chunks     = stripe.size;
          ss->column_id      = col_idx;
          ss->stream_type    = k;
        }
      }
    }

    stripes[stripe.id].numberOfRows =
      stripe.size == 0 ? 0
                       : segmentation.rowgroups[stripe.first + stripe.size - 1][0].end -
                           segmentation.rowgroups[stripe.first][0].begin;
  }

  strm_desc->host_to_device(stream);
  gpu::CompactOrcDataStreams(*strm_desc, *enc_streams, stream);
  strm_desc->device_to_host(stream);
  enc_streams->device_to_host(stream, true);

  return stripes;
}

void set_stat_desc_leaf_cols(device_span<orc_column_device_view const> columns,
                             device_span<stats_column_desc> stat_desc,
                             rmm::cuda_stream_view stream)
{
  thrust::for_each(rmm::exec_policy(stream),
                   thrust::make_counting_iterator(0ul),
                   thrust::make_counting_iterator(stat_desc.size()),
                   [=] __device__(auto idx) { stat_desc[idx].leaf_column = &columns[idx]; });
}

std::vector<std::vector<uint8_t>> writer::impl::gather_statistic_blobs(
  orc_table_view const& orc_table, file_segmentation const& segmentation)
{
  auto const num_stat_blobs = (1 + segmentation.num_stripes()) * orc_table.num_columns();

  hostdevice_vector<stats_column_desc> stat_desc(orc_table.num_columns(), stream);
  hostdevice_vector<statistics_merge_group> stat_merge(num_stat_blobs, stream);

  for (auto const& column : orc_table.columns) {
    stats_column_desc* desc = &stat_desc[column.index()];
    switch (column.orc_kind()) {
      case TypeKind::BYTE: desc->stats_dtype = dtype_int8; break;
      case TypeKind::SHORT: desc->stats_dtype = dtype_int16; break;
      case TypeKind::INT: desc->stats_dtype = dtype_int32; break;
      case TypeKind::LONG: desc->stats_dtype = dtype_int64; break;
      case TypeKind::FLOAT: desc->stats_dtype = dtype_float32; break;
      case TypeKind::DOUBLE: desc->stats_dtype = dtype_float64; break;
      case TypeKind::BOOLEAN: desc->stats_dtype = dtype_bool; break;
      case TypeKind::DATE: desc->stats_dtype = dtype_int32; break;
      case TypeKind::DECIMAL: desc->stats_dtype = dtype_decimal64; break;
      case TypeKind::TIMESTAMP: desc->stats_dtype = dtype_timestamp64; break;
      case TypeKind::STRING: desc->stats_dtype = dtype_string; break;
      default: desc->stats_dtype = dtype_none; break;
    }
    desc->num_rows   = column.size();
    desc->num_values = column.size();
    if (desc->stats_dtype == dtype_timestamp64) {
      // Timestamp statistics are in milliseconds
      switch (column.scale()) {
        case 9: desc->ts_scale = 1000; break;
        case 6: desc->ts_scale = 0; break;
        case 3: desc->ts_scale = -1000; break;
        case 0: desc->ts_scale = -1000000; break;
        default: desc->ts_scale = 0; break;
      }
    } else {
      desc->ts_scale = 0;
    }
    for (auto const& stripe : segmentation.stripes) {
      auto grp = &stat_merge[column.index() * segmentation.num_stripes() + stripe.id];
      grp->col = stat_desc.device_ptr(column.index());
      grp->start_chunk =
        static_cast<uint32_t>(column.index() * segmentation.num_rowgroups() + stripe.first);
      grp->num_chunks = stripe.size;
    }
    statistics_merge_group* col_stats =
      &stat_merge[segmentation.num_stripes() * orc_table.num_columns() + column.index()];
    col_stats->col         = stat_desc.device_ptr(column.index());
    col_stats->start_chunk = static_cast<uint32_t>(column.index() * segmentation.num_stripes());
    col_stats->num_chunks  = static_cast<uint32_t>(segmentation.num_stripes());
  }
  stat_desc.host_to_device(stream);
  stat_merge.host_to_device(stream);
  set_stat_desc_leaf_cols(orc_table.d_columns, stat_desc, stream);

  auto const num_chunks = segmentation.rowgroups.count();
  rmm::device_uvector<statistics_chunk> stat_chunks(num_chunks + num_stat_blobs, stream);
  rmm::device_uvector<statistics_group> stat_groups(num_chunks, stream);
  gpu::orc_init_statistics_groups(
    stat_groups.data(), stat_desc.device_ptr(), segmentation.rowgroups, stream);

  detail::calculate_group_statistics<detail::io_file_format::ORC>(
    stat_chunks.data(), stat_groups.data(), num_chunks, stream);
  detail::merge_group_statistics<detail::io_file_format::ORC>(
    stat_chunks.data() + num_chunks,
    stat_chunks.data(),
    stat_merge.device_ptr(),
    segmentation.num_stripes() * orc_table.num_columns(),
    stream);

  detail::merge_group_statistics<detail::io_file_format::ORC>(
    stat_chunks.data() + num_chunks + segmentation.num_stripes() * orc_table.num_columns(),
    stat_chunks.data() + num_chunks,
    stat_merge.device_ptr(segmentation.num_stripes() * orc_table.num_columns()),
    orc_table.num_columns(),
    stream);
  gpu::orc_init_statistics_buffersize(
    stat_merge.device_ptr(), stat_chunks.data() + num_chunks, num_stat_blobs, stream);
  stat_merge.device_to_host(stream, true);

  hostdevice_vector<uint8_t> blobs(
    stat_merge[num_stat_blobs - 1].start_chunk + stat_merge[num_stat_blobs - 1].num_chunks, stream);
  gpu::orc_encode_statistics(blobs.device_ptr(),
                             stat_merge.device_ptr(),
                             stat_chunks.data() + num_chunks,
                             num_stat_blobs,
                             stream);
  stat_merge.device_to_host(stream);
  blobs.device_to_host(stream, true);

  std::vector<std::vector<uint8_t>> stat_blobs(num_stat_blobs);
  for (size_t i = 0; i < num_stat_blobs; i++) {
    const uint8_t* stat_begin = blobs.host_ptr(stat_merge[i].start_chunk);
    const uint8_t* stat_end   = stat_begin + stat_merge[i].num_chunks;
    stat_blobs[i].assign(stat_begin, stat_end);
  }

  return stat_blobs;
}

void writer::impl::write_index_stream(int32_t stripe_id,
                                      int32_t stream_id,
                                      host_span<orc_column_view const> columns,
                                      stripe_rowgroups const& rowgroups_range,
                                      host_2dspan<gpu::encoder_chunk_streams const> enc_streams,
                                      host_2dspan<gpu::StripeStream const> strm_desc,
                                      host_span<gpu_inflate_status_s const> comp_out,
                                      StripeInformation* stripe,
                                      orc_streams* streams,
                                      ProtobufWriter* pbw)
{
  row_group_index_info present;
  row_group_index_info data;
  row_group_index_info data2;
  auto kind            = TypeKind::STRUCT;
  auto const column_id = stream_id - 1;

  auto find_record = [=, &strm_desc](gpu::encoder_chunk_streams const& stream,
                                     gpu::StreamIndexType type) {
    row_group_index_info record;
    if (stream.ids[type] > 0) {
      record.pos = 0;
      if (compression_kind_ != NONE) {
        auto const& ss   = strm_desc[stripe_id][stream.ids[type] - (columns.size() + 1)];
        record.blk_pos   = ss.first_block;
        record.comp_pos  = 0;
        record.comp_size = ss.stream_size;
      }
    }
    return record;
  };
  auto scan_record = [=, &comp_out](gpu::encoder_chunk_streams const& stream,
                                    gpu::StreamIndexType type,
                                    row_group_index_info& record) {
    if (record.pos >= 0) {
      record.pos += stream.lengths[type];
      while ((record.pos >= 0) && (record.blk_pos >= 0) &&
             (static_cast<size_t>(record.pos) >= compression_blocksize_) &&
             (record.comp_pos + BLOCK_HEADER_SIZE + comp_out[record.blk_pos].bytes_written <
              static_cast<size_t>(record.comp_size))) {
        record.pos -= compression_blocksize_;
        record.comp_pos += BLOCK_HEADER_SIZE + comp_out[record.blk_pos].bytes_written;
        record.blk_pos += 1;
      }
    }
  };

  // TBD: Not sure we need an empty index stream for column 0
  if (stream_id != 0) {
    const auto& strm = enc_streams[column_id][0];
    present          = find_record(strm, gpu::CI_PRESENT);
    data             = find_record(strm, gpu::CI_DATA);
    data2            = find_record(strm, gpu::CI_DATA2);

    // Change string dictionary to int from index point of view
    kind = columns[column_id].orc_kind();
    if (kind == TypeKind::STRING && columns[column_id].orc_encoding() == DICTIONARY_V2) {
      kind = TypeKind::INT;
    }
  }

  buffer_.resize((compression_kind_ != NONE) ? 3 : 0);

  // Add row index entries
  std::for_each(rowgroups_range.cbegin(), rowgroups_range.cend(), [&](auto rowgroup) {
    pbw->put_row_index_entry(
      present.comp_pos, present.pos, data.comp_pos, data.pos, data2.comp_pos, data2.pos, kind);

    if (stream_id != 0) {
      const auto& strm = enc_streams[column_id][rowgroup];
      scan_record(strm, gpu::CI_PRESENT, present);
      scan_record(strm, gpu::CI_DATA, data);
      scan_record(strm, gpu::CI_DATA2, data2);
    }
  });

  (*streams)[stream_id].length = buffer_.size();
  if (compression_kind_ != NONE) {
    uint32_t uncomp_ix_len = (uint32_t)((*streams)[stream_id].length - 3) * 2 + 1;
    buffer_[0]             = static_cast<uint8_t>(uncomp_ix_len >> 0);
    buffer_[1]             = static_cast<uint8_t>(uncomp_ix_len >> 8);
    buffer_[2]             = static_cast<uint8_t>(uncomp_ix_len >> 16);
  }
  out_sink_->host_write(buffer_.data(), buffer_.size());
  stripe->indexLength += buffer_.size();
}

void writer::impl::write_data_stream(gpu::StripeStream const& strm_desc,
                                     gpu::encoder_chunk_streams const& enc_stream,
                                     uint8_t const* compressed_data,
                                     uint8_t* stream_out,
                                     StripeInformation* stripe,
                                     orc_streams* streams)
{
  const auto length                                        = strm_desc.stream_size;
  (*streams)[enc_stream.ids[strm_desc.stream_type]].length = length;
  if (length == 0) { return; }

  const auto* stream_in = (compression_kind_ == NONE) ? enc_stream.data_ptrs[strm_desc.stream_type]
                                                      : (compressed_data + strm_desc.bfr_offset);

  if (out_sink_->is_device_write_preferred(length)) {
    out_sink_->device_write(stream_in, length, stream);
  } else {
    CUDA_TRY(
      cudaMemcpyAsync(stream_out, stream_in, length, cudaMemcpyDeviceToHost, stream.value()));
    stream.synchronize();

    out_sink_->host_write(stream_out, length);
  }
  stripe->dataLength += length;
}

void writer::impl::add_uncompressed_block_headers(std::vector<uint8_t>& v)
{
  if (compression_kind_ != NONE) {
    size_t uncomp_len = v.size() - 3, pos = 0, block_len;
    while (uncomp_len > compression_blocksize_) {
      block_len  = compression_blocksize_ * 2 + 1;
      v[pos + 0] = static_cast<uint8_t>(block_len >> 0);
      v[pos + 1] = static_cast<uint8_t>(block_len >> 8);
      v[pos + 2] = static_cast<uint8_t>(block_len >> 16);
      pos += 3 + compression_blocksize_;
      v.insert(v.begin() + pos, 3, 0);
      uncomp_len -= compression_blocksize_;
    }
    block_len  = uncomp_len * 2 + 1;
    v[pos + 0] = static_cast<uint8_t>(block_len >> 0);
    v[pos + 1] = static_cast<uint8_t>(block_len >> 8);
    v[pos + 2] = static_cast<uint8_t>(block_len >> 16);
  }
}

writer::impl::impl(std::unique_ptr<data_sink> sink,
                   orc_writer_options const& options,
                   SingleWriteMode mode,
                   rmm::cuda_stream_view stream,
                   rmm::mr::device_memory_resource* mr)
  : _mr(mr),
    stream(stream),
    compression_kind_(to_orc_compression(options.get_compression())),
    enable_statistics_(options.enable_statistics()),
    single_write_mode(mode == SingleWriteMode::YES),
    out_sink_(std::move(sink))
{
  if (options.get_metadata()) {
    table_meta = std::make_unique<table_input_metadata>(*options.get_metadata());
  }
  init_state();
}

writer::impl::impl(std::unique_ptr<data_sink> sink,
                   chunked_orc_writer_options const& options,
                   SingleWriteMode mode,
                   rmm::cuda_stream_view stream,
                   rmm::mr::device_memory_resource* mr)
  : _mr(mr),
    stream(stream),
    compression_kind_(to_orc_compression(options.get_compression())),
    enable_statistics_(options.enable_statistics()),
    single_write_mode(mode == SingleWriteMode::YES),
    out_sink_(std::move(sink))
{
  if (options.get_metadata()) {
    table_meta = std::make_unique<table_input_metadata>(*options.get_metadata());
  }
  init_state();
}

writer::impl::~impl() { close(); }

void writer::impl::init_state()
{
  // Write file header
  out_sink_->host_write(MAGIC, std::strlen(MAGIC));
}

void pushdown_lists_null_mask(orc_column_view const& col,
                              device_span<orc_column_device_view> d_columns,
                              bitmask_type const* parent_pd_mask,
                              device_span<bitmask_type> out_mask,
                              rmm::cuda_stream_view stream)
{
  // Set all bits - correct unless there's a mismatch between offsets and null mask
  CUDA_TRY(cudaMemsetAsync(static_cast<void*>(out_mask.data()),
                           255,
                           out_mask.size() * sizeof(bitmask_type),
                           stream.value()));

  // Reset bits where a null list element has rows in the child column
  thrust::for_each_n(
    rmm::exec_policy(stream),
    thrust::make_counting_iterator(0u),
    col.size(),
    [d_columns, col_idx = col.index(), parent_pd_mask, out_mask] __device__(auto& idx) {
      auto const d_col        = d_columns[col_idx];
      auto const is_row_valid = d_col.is_valid(idx) and bit_value_or(parent_pd_mask, idx, true);
      if (not is_row_valid) {
        auto offsets                = d_col.child(lists_column_view::offsets_column_index);
        auto const child_rows_begin = offsets.element<size_type>(idx + d_col.offset());
        auto const child_rows_end   = offsets.element<size_type>(idx + 1 + d_col.offset());
        for (auto child_row = child_rows_begin; child_row < child_rows_end; ++child_row)
          clear_bit(out_mask.data(), child_row);
      }
    });
}

/**
 * @brief All pushdown masks in a table.
 *
 * Pushdown masks are applied to child column(s). Only bits of the child column null mask that
 * correspond to set pushdown mask bits are encoded into the output file. Similarly, rows where
 * pushdown mask is 0 are treated as invalid and not included in the output.
 */
struct pushdown_null_masks {
  // Owning vector for masks in device memory
  std::vector<rmm::device_uvector<bitmask_type>> data;
  // Pointers to pushdown masks in device memory. Can be same for multiple columns.
  std::vector<bitmask_type const*> masks;
};

pushdown_null_masks init_pushdown_null_masks(orc_table_view& orc_table,
                                             rmm::cuda_stream_view stream)
{
  std::vector<bitmask_type const*> mask_ptrs;
  mask_ptrs.reserve(orc_table.num_columns());
  std::vector<rmm::device_uvector<bitmask_type>> pd_masks;
  for (auto const& col : orc_table.columns) {
    // Leaf columns don't need pushdown masks
    if (col.orc_kind() != LIST && col.orc_kind() != STRUCT) {
      mask_ptrs.emplace_back(nullptr);
      continue;
    }
    auto const parent_pd_mask = col.is_child() ? mask_ptrs[col.parent_index()] : nullptr;
    auto const null_mask      = col.null_mask();

    if (null_mask == nullptr and parent_pd_mask == nullptr) {
      mask_ptrs.emplace_back(nullptr);
      continue;
    }
    if (col.orc_kind() == STRUCT) {
      if (null_mask != nullptr and parent_pd_mask == nullptr) {
        // Reuse own null mask
        mask_ptrs.emplace_back(null_mask);
      } else if (null_mask == nullptr and parent_pd_mask != nullptr) {
        // Reuse parent's pushdown mask
        mask_ptrs.emplace_back(parent_pd_mask);
      } else {
        // Both are nullable, allocate new pushdown mask
        pd_masks.emplace_back(num_bitmask_words(col.size()), stream);
        mask_ptrs.emplace_back(pd_masks.back().data());

        thrust::transform(rmm::exec_policy(stream),
                          null_mask,
                          null_mask + pd_masks.back().size(),
                          parent_pd_mask,
                          pd_masks.back().data(),
                          thrust::bit_and<bitmask_type>());
      }
    }
    if (col.orc_kind() == LIST) {
      // Need a new pushdown mask unless both the parent and current colmn are not nullable
      auto const child_col = orc_table.column(col.child_begin()[0]);
      // pushdown mask applies to child column; use the child column size
      pd_masks.emplace_back(num_bitmask_words(child_col.size()), stream);
      mask_ptrs.emplace_back(pd_masks.back().data());
      pushdown_lists_null_mask(col, orc_table.d_columns, parent_pd_mask, pd_masks.back(), stream);
    }
  }

  // Attach null masks to device column views (async)
  auto const d_mask_ptrs = cudf::detail::make_device_uvector_async(mask_ptrs, stream);
  thrust::for_each_n(
    rmm::exec_policy(stream),
    thrust::make_counting_iterator(0ul),
    orc_table.num_columns(),
    [cols = device_span<orc_column_device_view>{orc_table.d_columns},
     ptrs = device_span<bitmask_type const* const>{d_mask_ptrs}] __device__(auto& idx) {
      cols[idx].pushdown_mask = ptrs[idx];
    });

  return {std::move(pd_masks), std::move(mask_ptrs)};
}

template <typename T>
struct device_stack {
  __device__ device_stack(T* stack_storage, int capacity)
    : stack(stack_storage), capacity(capacity), size(0)
  {
  }
  __device__ void push(T const& val)
  {
    cudf_assert(size < capacity and "Stack overflow");
    stack[size++] = val;
  }
  __device__ T pop()
  {
    cudf_assert(size > 0 and "Stack underflow");
    return stack[--size];
  }
  __device__ bool empty() { return size == 0; }

 private:
  T* stack;
  int capacity;
  int size;
};

orc_table_view make_orc_table_view(table_view const& table,
                                   table_device_view const& d_table,
                                   table_input_metadata const& table_meta,
                                   rmm::cuda_stream_view stream)
{
  std::vector<orc_column_view> orc_columns;
  std::vector<uint32_t> str_col_indexes;

  std::function<void(column_view const&, orc_column_view*, column_in_metadata const&)>
    append_orc_column =
      [&](column_view const& col, orc_column_view* parent_col, column_in_metadata const& col_meta) {
        int const str_idx =
          (col.type().id() == type_id::STRING) ? static_cast<int>(str_col_indexes.size()) : -1;

        auto const new_col_idx = orc_columns.size();
        orc_columns.emplace_back(new_col_idx, str_idx, parent_col, col, col_meta);
        if (orc_columns[new_col_idx].is_string()) { str_col_indexes.push_back(new_col_idx); }

        if (col.type().id() == type_id::LIST) {
          append_orc_column(col.child(lists_column_view::child_column_index),
                            &orc_columns[new_col_idx],
                            col_meta.child(lists_column_view::child_column_index));
        } else if (col.type().id() == type_id::STRUCT) {
          for (auto child_idx = 0; child_idx != col.num_children(); ++child_idx)
            append_orc_column(
              col.child(child_idx), &orc_columns[new_col_idx], col_meta.child(child_idx));
        }
      };

  for (auto col_idx = 0; col_idx < table.num_columns(); ++col_idx) {
    append_orc_column(table.column(col_idx), nullptr, table_meta.column_metadata[col_idx]);
  }

  rmm::device_uvector<orc_column_device_view> d_orc_columns(orc_columns.size(), stream);
  using stack_value_type = thrust::pair<column_device_view const*, thrust::optional<uint32_t>>;
  rmm::device_uvector<stack_value_type> stack_storage(orc_columns.size(), stream);

  // pre-order append ORC device columns
  cudf::detail::device_single_thread(
    [d_orc_cols         = device_span<orc_column_device_view>{d_orc_columns},
     d_table            = d_table,
     stack_storage      = stack_storage.data(),
     stack_storage_size = stack_storage.size()] __device__() {
      device_stack stack(stack_storage, stack_storage_size);

      thrust::for_each(thrust::seq,
                       thrust::make_reverse_iterator(d_table.end()),
                       thrust::make_reverse_iterator(d_table.begin()),
                       [&stack](column_device_view const& c) {
                         stack.push({&c, thrust::nullopt});
                       });

      uint32_t idx = 0;
      while (not stack.empty()) {
        auto [col, parent] = stack.pop();
        d_orc_cols[idx]    = orc_column_device_view{*col, parent};

        if (col->type().id() == type_id::LIST) {
          stack.push({&col->children()[lists_column_view::child_column_index], idx});
        } else if (col->type().id() == type_id::STRUCT) {
          thrust::for_each(thrust::seq,
                           thrust::make_reverse_iterator(col->children().end()),
                           thrust::make_reverse_iterator(col->children().begin()),
                           [&stack, idx](column_device_view const& c) {
                             stack.push({&c, idx});
                           });
        }
        idx++;
      }
    },
    stream);

  return {std::move(orc_columns),
          std::move(d_orc_columns),
          str_col_indexes,
          cudf::detail::make_device_uvector_sync(str_col_indexes, stream)};
}

hostdevice_2dvector<rowgroup_rows> calculate_rowgroup_bounds(orc_table_view const& orc_table,
                                                             size_type rowgroup_size,
                                                             rmm::cuda_stream_view stream)
{
  auto const num_rowgroups =
    cudf::util::div_rounding_up_unsafe<size_t, size_t>(orc_table.num_rows(), rowgroup_size);

  hostdevice_2dvector<rowgroup_rows> rowgroup_bounds(
    num_rowgroups, orc_table.num_columns(), stream);
  thrust::for_each_n(
    rmm::exec_policy(stream),
    thrust::make_counting_iterator(0ul),
    num_rowgroups,
    [cols      = device_span<orc_column_device_view const>{orc_table.d_columns},
     rg_bounds = device_2dspan<rowgroup_rows>{rowgroup_bounds},
     rowgroup_size] __device__(auto rg_idx) mutable {
      thrust::transform(
        thrust::seq, cols.begin(), cols.end(), rg_bounds[rg_idx].begin(), [&](auto const& col) {
          // Root column
          if (!col.parent_index.has_value()) {
            size_type const rows_begin = rg_idx * rowgroup_size;
            auto const rows_end = thrust::min<size_type>((rg_idx + 1) * rowgroup_size, col.size());
            return rowgroup_rows{rows_begin, rows_end};
          } else {
            // Child column
            auto const parent_index           = *col.parent_index;
            orc_column_device_view parent_col = cols[parent_index];
            auto const parent_rg              = rg_bounds[rg_idx][parent_index];
            if (parent_col.type().id() != type_id::LIST) {
              auto const offset_diff = parent_col.offset() - col.offset();
              return rowgroup_rows{parent_rg.begin + offset_diff, parent_rg.end + offset_diff};
            }

            auto offsets = parent_col.child(lists_column_view::offsets_column_index);
            auto const rows_begin =
              offsets.element<size_type>(parent_rg.begin + parent_col.offset()) - col.offset();
            auto const rows_end =
              offsets.element<size_type>(parent_rg.end + parent_col.offset()) - col.offset();

            return rowgroup_rows{rows_begin, rows_end};
          }
        });
    });
  rowgroup_bounds.device_to_host(stream, true);

  return rowgroup_bounds;
}

// returns host vector of per-rowgroup sizes
encoder_decimal_info decimal_chunk_sizes(orc_table_view& orc_table,
                                         file_segmentation const& segmentation,
                                         rmm::cuda_stream_view stream)
{
  std::map<uint32_t, rmm::device_uvector<uint32_t>> elem_sizes;
  // Compute per-element offsets (within each row group) on the device
  for (auto& orc_col : orc_table.columns) {
    if (orc_col.orc_kind() == DECIMAL) {
      auto& current_sizes =
        elem_sizes.insert({orc_col.index(), rmm::device_uvector<uint32_t>(orc_col.size(), stream)})
          .first->second;
      thrust::tabulate(rmm::exec_policy(stream),
                       current_sizes.begin(),
                       current_sizes.end(),
                       [d_cols  = device_span<orc_column_device_view const>{orc_table.d_columns},
                        col_idx = orc_col.index()] __device__(auto idx) {
                         auto const& col          = d_cols[col_idx];
                         auto const pushdown_mask = [&]() -> cudf::bitmask_type const* {
                           auto const parent_index = d_cols[col_idx].parent_index;
                           if (!parent_index.has_value()) return nullptr;
                           return d_cols[parent_index.value()].pushdown_mask;
                         }();
                         if (col.is_null(idx) or not bit_value_or(pushdown_mask, idx, true))
                           return 0u;
                         int64_t const element   = (col.type().id() == type_id::DECIMAL32)
                                                     ? col.element<int32_t>(idx)
                                                     : col.element<int64_t>(idx);
                         int64_t const sign      = (element < 0) ? 1 : 0;
                         uint64_t zigzaged_value = ((element ^ -sign) * 2) + sign;

                         uint32_t encoded_length = 1;
                         while (zigzaged_value > 127) {
                           zigzaged_value >>= 7u;
                           ++encoded_length;
                         }
                         return encoded_length;
                       });

      // Compute element offsets within each row group
      thrust::for_each_n(rmm::exec_policy(stream),
                         thrust::make_counting_iterator(0ul),
                         segmentation.num_rowgroups(),
                         [sizes     = device_span<uint32_t>{current_sizes},
                          rg_bounds = device_2dspan<rowgroup_rows const>{segmentation.rowgroups},
                          col_idx   = orc_col.index()] __device__(auto rg_idx) {
                           auto const& range = rg_bounds[rg_idx][col_idx];
                           thrust::inclusive_scan(thrust::seq,
                                                  sizes.begin() + range.begin,
                                                  sizes.begin() + range.end,
                                                  sizes.begin() + range.begin);
                         });

      orc_col.attach_decimal_offsets(current_sizes.data());
    }
  }
  if (elem_sizes.empty()) return {};

  // Gather the row group sizes and copy to host
  auto d_tmp_rowgroup_sizes = rmm::device_uvector<uint32_t>(segmentation.num_rowgroups(), stream);
  std::map<uint32_t, std::vector<uint32_t>> rg_sizes;
  for (auto const& [col_idx, esizes] : elem_sizes) {
    // Copy last elem in each row group - equal to row group size
    thrust::tabulate(rmm::exec_policy(stream),
                     d_tmp_rowgroup_sizes.begin(),
                     d_tmp_rowgroup_sizes.end(),
                     [src       = esizes.data(),
                      col_idx   = col_idx,
                      rg_bounds = device_2dspan<rowgroup_rows const>{
                        segmentation.rowgroups}] __device__(auto idx) {
                       return src[rg_bounds[idx][col_idx].end - 1];
                     });

    rg_sizes[col_idx] = cudf::detail::make_std_vector_async(d_tmp_rowgroup_sizes, stream);
  }

  return {std::move(elem_sizes), std::move(rg_sizes)};
}

std::map<uint32_t, size_t> decimal_column_sizes(
  std::map<uint32_t, std::vector<uint32_t>> const& chunk_sizes)
{
  std::map<uint32_t, size_t> column_sizes;
  std::transform(chunk_sizes.cbegin(),
                 chunk_sizes.cend(),
                 std::inserter(column_sizes, column_sizes.end()),
                 [](auto const& chunk_size) -> std::pair<uint32_t, size_t> {
                   return {
                     chunk_size.first,
                     std::accumulate(chunk_size.second.cbegin(), chunk_size.second.cend(), 0lu)};
                 });
  return column_sizes;
}

string_dictionaries allocate_dictionaries(orc_table_view const& orc_table,
                                          host_2dspan<rowgroup_rows const> rowgroup_bounds,
                                          rmm::cuda_stream_view stream)
{
  thrust::host_vector<bool> is_dict_enabled(orc_table.num_columns());
  for (auto col_idx : orc_table.string_column_indices)
    is_dict_enabled[col_idx] = std::all_of(
      thrust::make_counting_iterator(0ul),
      thrust::make_counting_iterator(rowgroup_bounds.size().first),
      [&](auto rg_idx) {
        return rowgroup_bounds[rg_idx][col_idx].size() < std::numeric_limits<uint16_t>::max();
      });

  std::vector<rmm::device_uvector<uint32_t>> data;
  std::transform(orc_table.string_column_indices.begin(),
                 orc_table.string_column_indices.end(),
                 std::back_inserter(data),
                 [&](auto& idx) {
                   return cudf::detail::make_zeroed_device_uvector_async<uint32_t>(
                     orc_table.columns[idx].size(), stream);
                 });
  std::vector<rmm::device_uvector<uint32_t>> index;
  std::transform(orc_table.string_column_indices.begin(),
                 orc_table.string_column_indices.end(),
                 std::back_inserter(index),
                 [&](auto& idx) {
                   return cudf::detail::make_zeroed_device_uvector_async<uint32_t>(
                     orc_table.columns[idx].size(), stream);
                 });
  stream.synchronize();

  std::vector<device_span<uint32_t>> data_ptrs;
  std::transform(data.begin(), data.end(), std::back_inserter(data_ptrs), [](auto& uvec) {
    return device_span<uint32_t>{uvec};
  });
  std::vector<device_span<uint32_t>> index_ptrs;
  std::transform(index.begin(), index.end(), std::back_inserter(index_ptrs), [](auto& uvec) {
    return device_span<uint32_t>{uvec};
  });

  return {std::move(data),
          std::move(index),
          cudf::detail::make_device_uvector_sync(data_ptrs, stream),
          cudf::detail::make_device_uvector_sync(index_ptrs, stream),
          std::move(is_dict_enabled)};
}

void writer::impl::write(table_view const& table)
{
  CUDF_EXPECTS(not closed, "Data has already been flushed to out and closed");
  auto const num_rows = table.num_rows();

  if (not table_meta) { table_meta = std::make_unique<table_input_metadata>(table); }

  // Fill unnamed columns' names in table_meta
  std::function<void(column_in_metadata&, std::string)> add_default_name =
    [&](column_in_metadata& col_meta, std::string default_name) {
      if (col_meta.get_name().empty()) col_meta.set_name(default_name);
      for (size_type i = 0; i < col_meta.num_children(); ++i) {
        add_default_name(col_meta.child(i), col_meta.get_name() + "." + std::to_string(i));
      }
    };
  for (size_t i = 0; i < table_meta->column_metadata.size(); ++i) {
    add_default_name(table_meta->column_metadata[i], "_col" + std::to_string(i));
  }

  auto const d_table = table_device_view::create(table, stream);

  auto orc_table = make_orc_table_view(table, *d_table, *table_meta, stream);

  auto const pd_masks = init_pushdown_null_masks(orc_table, stream);

  auto rowgroup_bounds = calculate_rowgroup_bounds(orc_table, row_index_stride_, stream);

  // Build per-column dictionary indices
  auto dictionaries = allocate_dictionaries(orc_table, rowgroup_bounds, stream);
  hostdevice_2dvector<gpu::DictionaryChunk> dict(
    rowgroup_bounds.size().first, orc_table.num_string_columns(), stream);
  if (orc_table.num_string_columns() != 0) {
    init_dictionaries(orc_table,
                      rowgroup_bounds,
                      dictionaries.d_data_view,
                      dictionaries.d_index_view,
                      &dict,
                      stream);
  }

  // Decide stripe boundaries based on rowgroups and dict chunks
  auto const segmentation =
    calculate_segmentation(orc_table.columns, std::move(rowgroup_bounds), max_stripe_size_);

  // Build stripe-level dictionaries
  hostdevice_2dvector<gpu::StripeDictionary> stripe_dict(
    segmentation.num_stripes(), orc_table.num_string_columns(), stream);
  if (orc_table.num_string_columns() != 0) {
    build_dictionaries(orc_table,
                       segmentation.stripes,
                       dict,
                       dictionaries.index,
                       dictionaries.dictionary_enabled,
                       stripe_dict);
  }

  auto dec_chunk_sizes = decimal_chunk_sizes(orc_table, segmentation, stream);

  auto streams =
    create_streams(orc_table.columns, segmentation, decimal_column_sizes(dec_chunk_sizes.rg_sizes));
  auto enc_data = encode_columns(
    orc_table, std::move(dictionaries), std::move(dec_chunk_sizes), segmentation, streams, stream);

  // Assemble individual disparate column chunks into contiguous data streams
  size_type const num_index_streams = (orc_table.num_columns() + 1);
  const auto num_data_streams       = streams.size() - num_index_streams;
  hostdevice_2dvector<gpu::StripeStream> strm_descs(
    segmentation.num_stripes(), num_data_streams, stream);
  auto stripes = gather_stripes(num_index_streams, segmentation, &enc_data.streams, &strm_descs);

  // Gather column statistics
  std::vector<ColStatsBlob> column_stats;
  if (enable_statistics_ && table.num_columns() > 0 && num_rows > 0) {
    column_stats = gather_statistic_blobs(orc_table, segmentation);
  }

  // Allocate intermediate output stream buffer
  size_t compressed_bfr_size       = 0;
  size_t num_compressed_blocks     = 0;
  size_t max_compressed_block_size = 0;
  if (compression_kind_ != NONE) {
    nvcompBatchedSnappyCompressGetMaxOutputChunkSize(
      compression_blocksize_, nvcompBatchedSnappyDefaultOpts, &max_compressed_block_size);
  }
  auto stream_output = [&]() {
    size_t max_stream_size = 0;
    bool all_device_write  = true;

    for (auto& ss : strm_descs.host_view().flat_view()) {
      if (!out_sink_->is_device_write_preferred(ss.stream_size)) { all_device_write = false; }
      size_t stream_size = ss.stream_size;
      if (compression_kind_ != NONE) {
        ss.first_block = num_compressed_blocks;
        ss.bfr_offset  = compressed_bfr_size;

        auto num_blocks = std::max<uint32_t>(
          (stream_size + compression_blocksize_ - 1) / compression_blocksize_, 1);
        stream_size += num_blocks * BLOCK_HEADER_SIZE;
        num_compressed_blocks += num_blocks;
        compressed_bfr_size += (max_compressed_block_size + BLOCK_HEADER_SIZE) * num_blocks;
      }
      max_stream_size = std::max(max_stream_size, stream_size);
    }

    if (all_device_write) {
      return pinned_buffer<uint8_t>{nullptr, cudaFreeHost};
    } else {
      return pinned_buffer<uint8_t>{[](size_t size) {
                                      uint8_t* ptr = nullptr;
                                      CUDA_TRY(cudaMallocHost(&ptr, size));
                                      return ptr;
                                    }(max_stream_size),
                                    cudaFreeHost};
    }
  }();

  // Compress the data streams
  rmm::device_buffer compressed_data(compressed_bfr_size, stream);
  hostdevice_vector<gpu_inflate_status_s> comp_out(num_compressed_blocks, stream);
  hostdevice_vector<gpu_inflate_input_s> comp_in(num_compressed_blocks, stream);
  if (compression_kind_ != NONE) {
    strm_descs.host_to_device(stream);
    gpu::CompressOrcDataStreams(static_cast<uint8_t*>(compressed_data.data()),
                                num_compressed_blocks,
                                compression_kind_,
                                compression_blocksize_,
                                max_compressed_block_size,
                                strm_descs,
                                enc_data.streams,
                                comp_in,
                                comp_out,
                                stream);
    strm_descs.device_to_host(stream);
    comp_out.device_to_host(stream, true);
  }

  ProtobufWriter pbw_(&buffer_);

  // Write stripes
  for (size_t stripe_id = 0; stripe_id < stripes.size(); ++stripe_id) {
    auto const& rowgroups_range = segmentation.stripes[stripe_id];
    auto& stripe                = stripes[stripe_id];

    stripe.offset = out_sink_->bytes_written();

    // Column (skippable) index streams appear at the start of the stripe
    for (size_type stream_id = 0; stream_id < num_index_streams; ++stream_id) {
      write_index_stream(stripe_id,
                         stream_id,
                         orc_table.columns,
                         rowgroups_range,
                         enc_data.streams,
                         strm_descs,
                         comp_out,
                         &stripe,
                         &streams,
                         &pbw_);
    }

    // Column data consisting one or more separate streams
    for (auto const& strm_desc : strm_descs[stripe_id]) {
      write_data_stream(strm_desc,
                        enc_data.streams[strm_desc.column_id][rowgroups_range.first],
                        static_cast<uint8_t*>(compressed_data.data()),
                        stream_output.get(),
                        &stripe,
                        &streams);
    }

    // Write stripefooter consisting of stream information
    StripeFooter sf;
    sf.streams = streams;
    sf.columns.resize(orc_table.num_columns() + 1);
    sf.columns[0].kind = DIRECT;
    for (size_t i = 1; i < sf.columns.size(); ++i) {
      sf.columns[i].kind = orc_table.column(i - 1).orc_encoding();
      sf.columns[i].dictionarySize =
        (sf.columns[i].kind == DICTIONARY_V2)
          ? orc_table.column(i - 1).host_stripe_dict(stripe_id)->num_strings
          : 0;
      if (orc_table.column(i - 1).orc_kind() == TIMESTAMP) { sf.writerTimezone = "UTC"; }
    }
    buffer_.resize((compression_kind_ != NONE) ? 3 : 0);
    pbw_.write(sf);
    stripe.footerLength = buffer_.size();
    if (compression_kind_ != NONE) {
      uint32_t uncomp_sf_len = (stripe.footerLength - 3) * 2 + 1;
      buffer_[0]             = static_cast<uint8_t>(uncomp_sf_len >> 0);
      buffer_[1]             = static_cast<uint8_t>(uncomp_sf_len >> 8);
      buffer_[2]             = static_cast<uint8_t>(uncomp_sf_len >> 16);
    }
    out_sink_->host_write(buffer_.data(), buffer_.size());
  }

  if (column_stats.size() != 0) {
    // File-level statistics
    // NOTE: Excluded from chunked write mode to avoid the need for merging stats across calls
    if (single_write_mode) {
      // First entry contains total number of rows
      buffer_.resize(0);
      pbw_.putb(1 * 8 + PB_TYPE_VARINT);
      pbw_.put_uint(num_rows);
      ff.statistics.reserve(1 + orc_table.num_columns());
      ff.statistics.emplace_back(std::move(buffer_));
      // Add file stats, stored after stripe stats in `column_stats`
      ff.statistics.insert(
        ff.statistics.end(),
        std::make_move_iterator(column_stats.begin()) + stripes.size() * orc_table.num_columns(),
        std::make_move_iterator(column_stats.end()));
    }
    // Stripe-level statistics
    size_t first_stripe = md.stripeStats.size();
    md.stripeStats.resize(first_stripe + stripes.size());
    for (size_t stripe_id = 0; stripe_id < stripes.size(); stripe_id++) {
      md.stripeStats[first_stripe + stripe_id].colStats.resize(1 + orc_table.num_columns());
      buffer_.resize(0);
      pbw_.putb(1 * 8 + PB_TYPE_VARINT);
      pbw_.put_uint(stripes[stripe_id].numberOfRows);
      md.stripeStats[first_stripe + stripe_id].colStats[0] = std::move(buffer_);
      for (size_t col_idx = 0; col_idx < orc_table.num_columns(); col_idx++) {
        size_t idx = stripes.size() * col_idx + stripe_id;
        if (idx < column_stats.size()) {
          md.stripeStats[first_stripe + stripe_id].colStats[1 + col_idx] =
            std::move(column_stats[idx]);
        }
      }
    }
  }
  if (ff.headerLength == 0) {
    // First call
    ff.headerLength   = std::strlen(MAGIC);
    ff.rowIndexStride = row_index_stride_;
    ff.types.resize(1 + orc_table.num_columns());
    ff.types[0].kind = STRUCT;
    for (auto const& column : orc_table.columns) {
      if (!column.is_child()) {
        ff.types[0].subtypes.emplace_back(column.id());
        ff.types[0].fieldNames.emplace_back(column.orc_name());
      }
    }
    for (auto const& column : orc_table.columns) {
      auto& schema_type = ff.types[column.id()];
      schema_type.kind  = column.orc_kind();
      if (column.orc_kind() == DECIMAL) {
        schema_type.scale     = static_cast<uint32_t>(column.scale());
        schema_type.precision = column.precision();
      }
      // In preorder traversal the column after a list column is always the child column
      if (column.orc_kind() == LIST) { schema_type.subtypes.emplace_back(column.id() + 1); }
      if (column.orc_kind() == STRUCT) {
        std::transform(column.child_begin(),
                       column.child_end(),
                       std::back_inserter(schema_type.subtypes),
                       [&](auto const& child_idx) { return orc_table.column(child_idx).id(); });
        std::transform(column.child_begin(),
                       column.child_end(),
                       std::back_inserter(schema_type.fieldNames),
                       [&](auto const& child_idx) {
                         return std::string{orc_table.column(child_idx).orc_name()};
                       });
      }
    }
  } else {
    // verify the user isn't passing mismatched tables
    CUDF_EXPECTS(ff.types.size() == 1 + orc_table.num_columns(),
                 "Mismatch in table structure between multiple calls to write");
    CUDF_EXPECTS(
      std::all_of(orc_table.columns.cbegin(),
                  orc_table.columns.cend(),
                  [&](auto const& col) { return ff.types[col.id()].kind == col.orc_kind(); }),
      "Mismatch in column types between multiple calls to write");
  }
  ff.stripes.insert(ff.stripes.end(),
                    std::make_move_iterator(stripes.begin()),
                    std::make_move_iterator(stripes.end()));
  ff.numberOfRows += num_rows;
}

void writer::impl::close()
{
  if (closed) { return; }
  closed = true;
  ProtobufWriter pbw_(&buffer_);
  PostScript ps;

  ff.contentLength = out_sink_->bytes_written();
  std::transform(table_meta->user_data.begin(),
                 table_meta->user_data.end(),
                 std::back_inserter(ff.metadata),
                 [&](auto const& udata) {
                   return UserMetadataItem{udata.first, udata.second};
                 });

  // Write statistics metadata
  if (md.stripeStats.size() != 0) {
    buffer_.resize((compression_kind_ != NONE) ? 3 : 0);
    pbw_.write(md);
    add_uncompressed_block_headers(buffer_);
    ps.metadataLength = buffer_.size();
    out_sink_->host_write(buffer_.data(), buffer_.size());
  } else {
    ps.metadataLength = 0;
  }
  buffer_.resize((compression_kind_ != NONE) ? 3 : 0);
  pbw_.write(ff);
  add_uncompressed_block_headers(buffer_);

  // Write postscript metadata
  ps.footerLength         = buffer_.size();
  ps.compression          = compression_kind_;
  ps.compressionBlockSize = compression_blocksize_;
  ps.version              = {0, 12};
  ps.magic                = MAGIC;
  const auto ps_length    = static_cast<uint8_t>(pbw_.write(ps));
  buffer_.push_back(ps_length);
  out_sink_->host_write(buffer_.data(), buffer_.size());
  out_sink_->flush();
}

// Forward to implementation
writer::writer(std::unique_ptr<data_sink> sink,
               orc_writer_options const& options,
               SingleWriteMode mode,
               rmm::cuda_stream_view stream,
               rmm::mr::device_memory_resource* mr)
  : _impl(std::make_unique<impl>(std::move(sink), options, mode, stream, mr))
{
}

// Forward to implementation
writer::writer(std::unique_ptr<data_sink> sink,
               chunked_orc_writer_options const& options,
               SingleWriteMode mode,
               rmm::cuda_stream_view stream,
               rmm::mr::device_memory_resource* mr)
  : _impl(std::make_unique<impl>(std::move(sink), options, mode, stream, mr))
{
}

// Destructor within this translation unit
writer::~writer() = default;

// Forward to implementation
void writer::write(table_view const& table) { _impl->write(table); }

// Forward to implementation
void writer::close() { _impl->close(); }

}  // namespace orc
}  // namespace detail
}  // namespace io
}  // namespace cudf
