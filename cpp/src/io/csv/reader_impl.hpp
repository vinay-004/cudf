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

#pragma once

#include "csv_common.h"
#include "csv_gpu.h"

#include <io/utilities/column_buffer.hpp>
#include <io/utilities/hostdevice_vector.hpp>
#include <io/utilities/trie.cuh>

#include <cudf/io/csv.hpp>
#include <cudf/io/datasource.hpp>
#include <cudf/io/detail/csv.hpp>
#include <cudf/utilities/span.hpp>

#include <rmm/cuda_stream_view.hpp>

#include <memory>
#include <string>
#include <utility>
#include <vector>

using cudf::host_span;

namespace cudf {
namespace io {
namespace detail {
namespace csv {
using namespace cudf::io::csv;
using namespace cudf::io;

/**
 * @brief Implementation for CSV reader
 *
 * The CSV reader is implemented in 4 stages:
 * Stage 1: read and optionally decompress the input data in host memory
 * (may be a memory-mapped view of the data on disk)
 *
 * Stage 2: gather the offset of each data row within the csv data.
 * Since the number of rows in a given character block may depend on the
 * initial parser state (like whether the block starts in a middle of a
 * quote or not), a separate row count and output parser state is computed
 * for every possible input parser state per 16KB character block.
 * The result is then used to infer the parser state and starting row at
 * the beginning of every character block.
 * A second pass can then output the location of every row (which is needed
 * for the subsequent parallel conversion of every row from csv text
 * to cudf binary form)
 *
 * Stage 3: Optional stage to infer the data type of each CSV column.
 *
 * Stage 4: Convert every row from csv text form to cudf binary form.
 */
class reader::impl {
 public:
  /**
   * @brief Constructor from a dataset source with reader options.
   *
   * @param source Dataset source
   * @param options Settings for controlling reading behavior
   * @param stream CUDA stream used for device memory operations and kernel launches
   * @param mr Device memory resource to use for device memory allocation
   */
  explicit impl(std::unique_ptr<datasource> source,
                csv_reader_options const& options,
                rmm::cuda_stream_view stream,
                rmm::mr::device_memory_resource* mr);

  /**
   * @brief Read an entire set or a subset of data and returns a set of columns.
   *
   * @param stream CUDA stream used for device memory operations and kernel launches.
   *
   * @return The set of columns along with metadata
   */
  table_with_metadata read(rmm::cuda_stream_view stream);

 private:
  /**
   * @brief Offsets of CSV rows in device memory, accessed through a shrinkable span.
   *
   * Row offsets are stored this way to avoid reallocation/copies when discarding front or back
   * elements.
   */
  class selected_rows_offsets {
    rmm::device_uvector<uint64_t> all;
    device_span<uint64_t const> selected;

   public:
    selected_rows_offsets(rmm::device_uvector<uint64_t>&& data,
                          device_span<uint64_t const> selected_span)
      : all{std::move(data)}, selected{selected_span}
    {
    }
    selected_rows_offsets(rmm::cuda_stream_view stream) : all{0, stream}, selected{all} {}

    operator device_span<uint64_t const>() const { return selected; }
    void shrink(size_t size)
    {
      CUDF_EXPECTS(size <= selected.size(), "New size must be smaller");
      selected = selected.subspan(0, size);
    }
    void erase_first_n(size_t n)
    {
      CUDF_EXPECTS(n <= selected.size(), "Too many elements to remove");
      selected = selected.subspan(n, selected.size() - n);
    }
    auto size() const { return selected.size(); }
    auto data() const { return selected.data(); }
  };

  /**
   * @brief Selectively loads data on the GPU and gathers offsets of rows to read.
   *
   * Selection is based on read options.
   *
   * @param stream CUDA stream used for device memory operations and kernel launches.
   */
  std::pair<rmm::device_uvector<char>, reader::impl::selected_rows_offsets>
  select_data_and_row_offsets(rmm::cuda_stream_view stream);

  /**
   * @brief Finds row positions in the specified input data, and loads the selected data onto GPU.
   *
   * This function scans the input data to record the row offsets (relative to the start of the
   * input data). A row is actually the data/offset between two termination symbols.
   *
   * @param data Uncompressed input data in host memory
   * @param range_begin Only include rows starting after this position
   * @param range_end Only include rows starting before this position
   * @param skip_rows Number of rows to skip from the start
   * @param num_rows Number of rows to read; -1: all remaining data
   * @param load_whole_file Hint that the entire data will be needed on gpu
   * @param stream CUDA stream used for device memory operations and kernel launches
   * @return Input data and row offsets in the device memory
   */
  std::pair<rmm::device_uvector<char>, reader::impl::selected_rows_offsets>
  load_data_and_gather_row_offsets(host_span<char const> data,
                                   size_t range_begin,
                                   size_t range_end,
                                   size_t skip_rows,
                                   int64_t num_rows,
                                   bool load_whole_file,
                                   rmm::cuda_stream_view stream);

  /**
   * @brief Find the start position of the first data row
   *
   * @param h_data Uncompressed input data in host memory
   *
   * @return Byte position of the first row
   */
  size_t find_first_row_start(host_span<char const> data);

  /**
   * @brief Automatically infers each column's data type based on the CSV's data within that column.
   *
   * @param data The CSV data from which to infer the columns' data types
   * @param row_offsets The row offsets into the CSV's data
   * @param stream The stream to which the type inference-kernel will be dispatched
   * @return The columns' inferred data types
   */
  std::vector<data_type> infer_column_types(device_span<char const> data,
                                            device_span<uint64_t const> row_offsets,
                                            rmm::cuda_stream_view stream);

  /**
   * @brief Selects the columns' data types from the map of dtypes.
   *
   * @param col_type_map Column name -> data type map specifying the columns' target data types
   * @return Sorted list of selected columns' data types
   */
  std::vector<data_type> select_data_types(std::map<std::string, data_type> const& col_type_map);

  /**
   * @brief Selects the columns' data types from the list of dtypes.
   *
   * @param dtypes Vector of data types specifying the columns' target data types
   * @return Sorted list of selected columns' data types
   */
  std::vector<data_type> select_data_types(std::vector<data_type> const& dtypes);

  /**
   * @brief Converts the row-column data and outputs to column bufferrs.
   *
   * @param column_types Column types
   * @param stream CUDA stream used for device memory operations and kernel launches.
   *
   * @return list of column buffers of decoded data, or ptr/size in the case of strings.
   */
  std::vector<column_buffer> decode_data(device_span<char const> data,
                                         device_span<uint64_t const> row_offsets,
                                         host_span<data_type const> column_types,
                                         rmm::cuda_stream_view stream);

 private:
  rmm::mr::device_memory_resource* mr_ = nullptr;
  std::unique_ptr<datasource> source_;
  const csv_reader_options opts_;

  cudf::size_type num_records_ = 0;  // Number of rows with actual data
  int num_active_cols_         = 0;  // Number of columns to read
  int num_actual_cols_         = 0;  // Number of columns in the dataset

  // Parsing options
  parse_options opts{};
  std::vector<column_parse::flags> column_flags_;

  // Intermediate data
  std::vector<std::string> col_names_;
  std::vector<char> header_;
};

}  // namespace csv
}  // namespace detail
}  // namespace io
}  // namespace cudf
