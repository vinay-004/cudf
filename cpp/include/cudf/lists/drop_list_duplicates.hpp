/*
 * Copyright (c) 2021, NVIDIA CORPORATION.
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

#include <cudf/column/column.hpp>
#include <cudf/lists/lists_column_view.hpp>
#include <cudf/stream_compaction.hpp>

namespace cudf {
namespace lists {
/**
 * @addtogroup lists_drop_duplicates
 * @{
 * @file
 */

/**
 * @brief Create a new lists column by extracting unique entries from list elements in the given
 * lists column.
 *
 * Given an input lists column, the list elements in the column are copied to an output lists
 * column such that their duplicated entries are dropped out to keep only the unique ones. The
 * order of those entries within each list are not guaranteed to be preserved as in the input. In
 * the current implementation, entries in the output lists are sorted by ascending order (nulls
 * last), but this is not guaranteed in future implementation.
 *
 * @throw cudf::logic_error if the child column of the input lists column contains nested type other
 * than struct.
 *
 * @param lists_column The input lists column to extract lists with unique entries.
 * @param nulls_equal Flag to specify whether null entries should be considered equal.
 * @param nans_equal Flag to specify whether NaN entries should be considered as equal value (only
 *        applicable for floating point data column).
 * @param mr Device resource used to allocate memory.
 *
 * @code{.pseudo}
 * input  = { {1, 1, 2, 1, 3}, {4}, NULL, {}, {NULL, NULL, NULL, 5, 6, 6, 6, 5} }
 * output = { {1, 2, 3}, {4}, NULL, {}, {5, 6, NULL} }
 *
 * Note that permuting the entries of each list in this output also produces another valid output.
 * @endcode
 *
 * @return A lists column with list elements having unique entries.
 */
std::unique_ptr<column> drop_list_duplicates(
  lists_column_view const& lists_column,
  null_equality nulls_equal           = null_equality::EQUAL,
  nan_equality nans_equal             = nan_equality::UNEQUAL,
  rmm::mr::device_memory_resource* mr = rmm::mr::get_current_device_resource());

/** @} */  // end of group
}  // namespace lists
}  // namespace cudf
