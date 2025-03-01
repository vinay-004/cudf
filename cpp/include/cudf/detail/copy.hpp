/*
 * Copyright (c) 2018-2021, NVIDIA CORPORATION.
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

#include <cudf/column/column_view.hpp>
#include <cudf/copying.hpp>
#include <cudf/types.hpp>
#include <cudf/utilities/traits.hpp>

#include <rmm/cuda_stream_view.hpp>

namespace cudf {
namespace detail {
/**
 * @brief Constructs a zero-copy `column_view`/`mutable_column_view` of the
 * elements in the range `[begin,end)` in `input`.
 *
 * @note It is the caller's responsibility to ensure that the returned view
 * does not outlive the viewed device memory.
 *
 * @throws cudf::logic_error if `begin < 0`, `end < begin` or
 * `end > input.size()`.
 *
 * @param[in] input View of input column to slice
 * @param[in] begin Index of the first desired element in the slice (inclusive).
 * @param[in] end Index of the last desired element in the slice (exclusive).
 *
 * @return ColumnView View of the elements `[begin,end)` from `input`.
 */
template <typename ColumnView>
ColumnView slice(ColumnView const& input, cudf::size_type begin, cudf::size_type end)
{
  static_assert(std::is_same_v<ColumnView, cudf::column_view> or
                  std::is_same_v<ColumnView, cudf::mutable_column_view>,
                "slice can be performed only on column_view and mutable_column_view");
  CUDF_EXPECTS(begin >= 0, "Invalid beginning of range.");
  CUDF_EXPECTS(end >= begin, "Invalid end of range.");
  CUDF_EXPECTS(end <= input.size(), "Slice range out of bounds.");

  std::vector<ColumnView> children{};
  children.reserve(input.num_children());
  for (size_type index = 0; index < input.num_children(); index++) {
    children.emplace_back(input.child(index));
  }

  return ColumnView(input.type(),
                    end - begin,
                    input.head(),
                    input.null_mask(),
                    cudf::UNKNOWN_NULL_COUNT,
                    input.offset() + begin,
                    children);
}

/**
 * @copydoc cudf::slice(column_view const&,std::vector<size_type> const&)
 *
 * @param stream CUDA stream used for device memory operations and kernel launches.
 */
std::vector<column_view> slice(column_view const& input,
                               std::vector<size_type> const& indices,
                               rmm::cuda_stream_view stream = rmm::cuda_stream_default);

/**
 * @copydoc cudf::shift(column_view const&,size_type,scalar const&,
 * rmm::mr::device_memory_resource*)
 *
 * @param stream CUDA stream used for device memory operations and kernel launches.
 */
std::unique_ptr<column> shift(
  column_view const& input,
  size_type offset,
  scalar const& fill_value,
  rmm::cuda_stream_view stream        = rmm::cuda_stream_default,
  rmm::mr::device_memory_resource* mr = rmm::mr::get_current_device_resource());

/**
 * @brief Performs segmented shifts for specified values.
 *
 * For each segment, `i`th element is determined by the `i - offset`th element
 * of the segment. If `i - offset < 0 or >= segment_size`, the value is determined by
 * @p fill_value.
 *
 * Example:
 * @code{.pseudo}
 * segmented_values: { 3 1 2 | 3 5 3 | 2 6 }
 * segment_offsets: {0 3 6 8}
 * offset: 2
 * fill_value: @
 * result: { @ @ 3 | @ @ 3 | @ @ }
 * -------------------------------------------------
 * segmented_values: { 3 1 2 | 3 5 3 | 2 6 }
 * segment_offsets: {0 3 6 8}
 * offset: -1
 * fill_value: -1
 * result: { 1 2 -1 | 5 3 -1 | 6 -1 }
 * @endcode
 *
 * @param segmented_values Segmented column, specified by @p segment_offsets
 * @param segment_offsets Each segment's offset of @p segmented_values. A list of offsets
 * with size `num_segments + 1`. The size of each segment is `segment_offsets[i+1] -
 * segment_offsets[i]`.
 * @param offset The offset by which to shift the input
 * @param fill_value Fill value for indeterminable outputs
 * @param stream CUDA stream used for device memory operations and kernel launches.
 * @param mr Device memory resource used to allocate the returned table and columns' device memory
 *
 * @note If `offset == 0`, a copy of @p segmented_values is returned.
 */
std::unique_ptr<column> segmented_shift(
  column_view const& segmented_values,
  device_span<size_type const> segment_offsets,
  size_type offset,
  scalar const& fill_value,
  rmm::cuda_stream_view stream        = rmm::cuda_stream_default,
  rmm::mr::device_memory_resource* mr = rmm::mr::get_current_device_resource());

/**
 * @copydoc cudf::contiguous_split
 *
 * @param stream CUDA stream used for device memory operations and kernel launches.
 **/
std::vector<packed_table> contiguous_split(
  cudf::table_view const& input,
  std::vector<size_type> const& splits,
  rmm::cuda_stream_view stream        = rmm::cuda_stream_default,
  rmm::mr::device_memory_resource* mr = rmm::mr::get_current_device_resource());

/**
 * @copydoc cudf::pack
 *
 * @param stream Optional CUDA stream on which to execute kernels
 **/
packed_columns pack(cudf::table_view const& input,
                    rmm::cuda_stream_view stream        = rmm::cuda_stream_default,
                    rmm::mr::device_memory_resource* mr = rmm::mr::get_current_device_resource());

/**
 * @copydoc cudf::allocate_like(column_view const&, size_type, mask_allocation_policy,
 * rmm::mr::device_memory_resource*)
 *
 * @param[in] stream CUDA stream used for device memory operations and kernel launches.
 */
std::unique_ptr<column> allocate_like(
  column_view const& input,
  size_type size,
  mask_allocation_policy mask_alloc   = mask_allocation_policy::RETAIN,
  rmm::cuda_stream_view stream        = rmm::cuda_stream_default,
  rmm::mr::device_memory_resource* mr = rmm::mr::get_current_device_resource());

/**
 * @copydoc cudf::copy_if_else( column_view const&, column_view const&,
 * column_view const&, rmm::mr::device_memory_resource*)
 *
 * @param[in] stream CUDA stream used for device memory operations and kernel launches.
 */
std::unique_ptr<column> copy_if_else(
  column_view const& lhs,
  column_view const& rhs,
  column_view const& boolean_mask,
  rmm::cuda_stream_view stream        = rmm::cuda_stream_default,
  rmm::mr::device_memory_resource* mr = rmm::mr::get_current_device_resource());

/**
 * @copydoc cudf::copy_if_else( scalar const&, column_view const&,
 * column_view const&, rmm::mr::device_memory_resource*)
 *
 * @param[in] stream CUDA stream used for device memory operations and kernel launches.
 */
std::unique_ptr<column> copy_if_else(
  scalar const& lhs,
  column_view const& rhs,
  column_view const& boolean_mask,
  rmm::cuda_stream_view stream        = rmm::cuda_stream_default,
  rmm::mr::device_memory_resource* mr = rmm::mr::get_current_device_resource());

/**
 * @copydoc cudf::copy_if_else( column_view const&, scalar const&,
 * column_view const&, rmm::mr::device_memory_resource*)
 *
 * @param[in] stream CUDA stream used for device memory operations and kernel launches.
 */
std::unique_ptr<column> copy_if_else(
  column_view const& lhs,
  scalar const& rhs,
  column_view const& boolean_mask,
  rmm::cuda_stream_view stream        = rmm::cuda_stream_default,
  rmm::mr::device_memory_resource* mr = rmm::mr::get_current_device_resource());

/**
 * @copydoc cudf::copy_if_else( scalar const&, scalar const&,
 * column_view const&, rmm::mr::device_memory_resource*)
 *
 * @param[in] stream CUDA stream used for device memory operations and kernel launches.
 */
std::unique_ptr<column> copy_if_else(
  scalar const& lhs,
  scalar const& rhs,
  column_view const& boolean_mask,
  rmm::cuda_stream_view stream        = rmm::cuda_stream_default,
  rmm::mr::device_memory_resource* mr = rmm::mr::get_current_device_resource());

/**
 * @copydoc cudf::sample
 *
 * @param[in] stream CUDA stream used for device memory operations and kernel launches.
 */
std::unique_ptr<table> sample(
  table_view const& input,
  size_type const n,
  sample_with_replacement replacement = sample_with_replacement::FALSE,
  int64_t const seed                  = 0,
  rmm::cuda_stream_view stream        = rmm::cuda_stream_default,
  rmm::mr::device_memory_resource* mr = rmm::mr::get_current_device_resource());

/**
 * @copydoc cudf::get_element
 *
 * @param[in] stream CUDA stream used for device memory operations and kernel launches.
 */
std::unique_ptr<scalar> get_element(
  column_view const& input,
  size_type index,
  rmm::cuda_stream_view stream        = rmm::cuda_stream_default,
  rmm::mr::device_memory_resource* mr = rmm::mr::get_current_device_resource());
}  // namespace detail
}  // namespace cudf
