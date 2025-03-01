/*
 *
 *  Copyright (c) 2019-2021, NVIDIA CORPORATION.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package ai.rapids.cudf;

import ai.rapids.cudf.HostColumnVector.BasicType;
import ai.rapids.cudf.HostColumnVector.Builder;
import ai.rapids.cudf.HostColumnVector.DataType;
import ai.rapids.cudf.HostColumnVector.ListType;
import ai.rapids.cudf.HostColumnVector.StructData;
import ai.rapids.cudf.HostColumnVector.StructType;

import ai.rapids.cudf.ast.BinaryOperation;
import ai.rapids.cudf.ast.BinaryOperator;
import ai.rapids.cudf.ast.ColumnReference;
import ai.rapids.cudf.ast.CompiledExpression;
import ai.rapids.cudf.ast.TableReference;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.util.HadoopInputFile;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.OriginalType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

import static ai.rapids.cudf.ParquetColumnWriterOptions.mapColumn;
import static ai.rapids.cudf.ParquetWriterOptions.listBuilder;
import static ai.rapids.cudf.ParquetWriterOptions.structBuilder;
import static ai.rapids.cudf.Table.TestBuilder;
import static ai.rapids.cudf.Table.removeNullMasksIfNeeded;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TableTest extends CudfTestBase {
  private static final File TEST_PARQUET_FILE = new File("src/test/resources/acq.parquet");
  private static final File TEST_ORC_FILE = new File("src/test/resources/TestOrcFile.orc");
  private static final File TEST_ORC_TIMESTAMP_DATE_FILE = new File(
      "src/test/resources/timestamp-date-test.orc");
  private static final File TEST_DECIMAL_PARQUET_FILE = new File("src/test/resources/decimal.parquet");

  private static final Schema CSV_DATA_BUFFER_SCHEMA = Schema.builder()
      .column(DType.INT32, "A")
      .column(DType.FLOAT64, "B")
      .column(DType.INT64, "C")
      .build();

  private static final byte[] CSV_DATA_BUFFER = ("A|B|C\n" +
      "'0'|'110.0'|'120'\n" +
      "1|111.0|121\n" +
      "2|112.0|122\n" +
      "3|113.0|123\n" +
      "4|114.0|124\n" +
      "5|115.0|125\n" +
      "6|116.0|126\n" +
      "7|NULL|127\n" +
      "8|118.2|128\n" +
      "9|119.8|129").getBytes(StandardCharsets.UTF_8);

  /**
   * Checks and asserts that passed in columns match
   * @param expect The expected result column
   * @param cv The input column
   */
  public static void assertColumnsAreEqual(ColumnView expect, ColumnView cv) {
    assertColumnsAreEqual(expect, cv, "unnamed");
  }

  /**
   * Checks and asserts that passed in columns match
   * @param expected The expected result column
   * @param cv The input column
   * @param colName The name of the column
   */
  public static void assertColumnsAreEqual(ColumnView expected, ColumnView cv, String colName) {
    assertPartialColumnsAreEqual(expected, 0, expected.getRowCount(), cv, colName, true, false);
  }

  /**
   * Checks and asserts that passed in host columns match
   * @param expected The expected result host column
   * @param cv The input host column
   * @param colName The name of the host column
   */
  public static void assertColumnsAreEqual(HostColumnVector expected, HostColumnVector cv, String colName) {
    assertPartialColumnsAreEqual(expected, 0, expected.getRowCount(), cv, colName, true, false);
  }

  /**
   * Checks and asserts that passed in Struct columns match
   * @param expected The expected result Struct column
   * @param cv The input Struct column
   */
  public static void assertStructColumnsAreEqual(ColumnView expected, ColumnView cv) {
    assertPartialStructColumnsAreEqual(expected, 0, expected.getRowCount(), cv, "unnamed", true, false);
  }

  /**
   * Checks and asserts that passed in Struct columns match
   * @param expected The expected result Struct column
   * @param rowOffset The row number to look from
   * @param length The number of rows to consider
   * @param cv The input Struct column
   * @param colName The name of the column
   * @param enableNullCountCheck Whether to check for nulls in the Struct column
   * @param enableNullabilityCheck Whether the table have a validity mask
   */
  public static void assertPartialStructColumnsAreEqual(ColumnView expected, long rowOffset, long length,
      ColumnView cv, String colName, boolean enableNullCountCheck, boolean enableNullabilityCheck) {
    try (HostColumnVector hostExpected = expected.copyToHost();
         HostColumnVector hostcv = cv.copyToHost()) {
      assertPartialColumnsAreEqual(hostExpected, rowOffset, length, hostcv, colName, enableNullCountCheck, enableNullabilityCheck);
    }
  }

  /**
   * Checks and asserts that passed in columns match
   * @param expected The expected result column
   * @param cv The input column
   * @param colName The name of the column
   * @param enableNullCheck Whether to check for nulls in the column
   * @param enableNullabilityCheck Whether the table have a validity mask
   */
  public static void assertPartialColumnsAreEqual(ColumnView expected, long rowOffset, long length,
      ColumnView cv, String colName, boolean enableNullCheck, boolean enableNullabilityCheck) {
    try (HostColumnVector hostExpected = expected.copyToHost();
         HostColumnVector hostcv = cv.copyToHost()) {
      assertPartialColumnsAreEqual(hostExpected, rowOffset, length, hostcv, colName, enableNullCheck, enableNullabilityCheck);
    }
  }

  /**
   * Checks and asserts that passed in host columns match
   * @param expected The expected result host column
   * @param rowOffset start row index
   * @param length  number of rows from starting offset
   * @param cv The input host column
   * @param colName The name of the host column
   * @param enableNullCountCheck Whether to check for nulls in the host column
   */
  public static void assertPartialColumnsAreEqual(HostColumnVectorCore expected, long rowOffset, long length,
                                                  HostColumnVectorCore cv, String colName, boolean enableNullCountCheck, boolean enableNullabilityCheck) {
    assertEquals(expected.getType(), cv.getType(), "Type For Column " + colName);
    assertEquals(length, cv.getRowCount(), "Row Count For Column " + colName);
    assertEquals(expected.getNumChildren(), cv.getNumChildren(), "Child Count for Column " + colName);
    if (enableNullCountCheck) {
      assertEquals(expected.getNullCount(), cv.getNullCount(), "Null Count For Column " + colName);
    } else {
      // TODO add in a proper check when null counts are supported by serializing a partitioned column
    }
    if (enableNullabilityCheck) {
      assertEquals(expected.hasValidityVector(), cv.hasValidityVector(), "Column nullability is different than expected");
    }
    DType type = expected.getType();
    for (long expectedRow = rowOffset; expectedRow < (rowOffset + length); expectedRow++) {
      long tableRow = expectedRow - rowOffset;
      assertEquals(expected.isNull(expectedRow), cv.isNull(tableRow),
          "NULL for Column " + colName + " Row " + tableRow);
      if (!expected.isNull(expectedRow)) {
        switch (type.typeId) {
          case BOOL8: // fall through
          case INT8: // fall through
          case UINT8:
            assertEquals(expected.getByte(expectedRow), cv.getByte(tableRow),
                "Column " + colName + " Row " + tableRow);
            break;
          case INT16: // fall through
          case UINT16:
            assertEquals(expected.getShort(expectedRow), cv.getShort(tableRow),
                "Column " + colName + " Row " + tableRow);
            break;
          case INT32: // fall through
          case UINT32: // fall through
          case TIMESTAMP_DAYS:
          case DURATION_DAYS:
          case DECIMAL32:
            assertEquals(expected.getInt(expectedRow), cv.getInt(tableRow),
                "Column " + colName + " Row " + tableRow);
            break;
          case INT64: // fall through
          case UINT64: // fall through
          case DURATION_MICROSECONDS: // fall through
          case DURATION_MILLISECONDS: // fall through
          case DURATION_NANOSECONDS: // fall through
          case DURATION_SECONDS: // fall through
          case TIMESTAMP_MICROSECONDS: // fall through
          case TIMESTAMP_MILLISECONDS: // fall through
          case TIMESTAMP_NANOSECONDS: // fall through
          case TIMESTAMP_SECONDS:
          case DECIMAL64:
            assertEquals(expected.getLong(expectedRow), cv.getLong(tableRow),
                "Column " + colName + " Row " + tableRow);
            break;
          case FLOAT32:
            assertEqualsWithinPercentage(expected.getFloat(expectedRow), cv.getFloat(tableRow), 0.0001,
                "Column " + colName + " Row " + tableRow);
            break;
          case FLOAT64:
            assertEqualsWithinPercentage(expected.getDouble(expectedRow), cv.getDouble(tableRow), 0.0001,
                "Column " + colName + " Row " + tableRow);
            break;
          case STRING:
            assertArrayEquals(expected.getUTF8(expectedRow), cv.getUTF8(tableRow),
                "Column " + colName + " Row " + tableRow);
            break;
          case LIST:
            HostMemoryBuffer expectedOffsets = expected.getOffsets();
            HostMemoryBuffer cvOffsets = cv.getOffsets();
            int expectedChildRows = expectedOffsets.getInt((expectedRow + 1) * 4) -
                expectedOffsets.getInt(expectedRow * 4);
            int cvChildRows = cvOffsets.getInt((tableRow + 1) * 4) -
                cvOffsets.getInt(tableRow * 4);
            assertEquals(expectedChildRows, cvChildRows, "Child row count for Column " +
                colName + " Row " + tableRow);
            break;
          case STRUCT:
            // parent column only has validity which was checked above
            break;
          default:
            throw new IllegalArgumentException(type + " is not supported yet");
        }
      }
    }

    if (type.isNestedType()) {
      switch (type.typeId) {
        case LIST:
          int expectedChildRowOffset = 0;
          int numChildRows = 0;
          if (length > 0) {
            HostMemoryBuffer expectedOffsets = expected.getOffsets();
            HostMemoryBuffer cvOffsets = cv.getOffsets();
            expectedChildRowOffset = expectedOffsets.getInt(rowOffset * 4);
            numChildRows = expectedOffsets.getInt((rowOffset + length) * 4) -
                expectedChildRowOffset;
          }
          assertPartialColumnsAreEqual(expected.getNestedChildren().get(0), expectedChildRowOffset,
              numChildRows, cv.getNestedChildren().get(0), colName + " list child",
              enableNullCountCheck, enableNullabilityCheck);
          break;
        case STRUCT:
          List<HostColumnVectorCore> expectedChildren = expected.getNestedChildren();
          List<HostColumnVectorCore> cvChildren = cv.getNestedChildren();
          for (int i = 0; i < expectedChildren.size(); i++) {
            HostColumnVectorCore expectedChild = expectedChildren.get(i);
            HostColumnVectorCore cvChild = cvChildren.get(i);
            String childName = colName + " child " + i;
            assertEquals(length, cvChild.getRowCount(), "Row Count for Column " + colName);
            assertPartialColumnsAreEqual(expectedChild, rowOffset, length, cvChild,
                colName, enableNullCountCheck, enableNullabilityCheck);
          }
          break;
        default:
          throw new IllegalArgumentException(type + " is not supported yet");
      }
    }
  }

  /**
   * Checks and asserts that the two tables from a given rowindex match based on a provided schema.
   * @param expected the expected result table
   * @param rowOffset the row number to start checking from
   * @param length the number of rows to check
   * @param table the input table to compare against expected
   * @param enableNullCheck whether to check for nulls or not
   * @param enableNullabilityCheck whether the table have a validity mask
   */
  public static void assertPartialTablesAreEqual(Table expected, long rowOffset, long length, Table table,
                                                 boolean enableNullCheck, boolean enableNullabilityCheck) {
    assertEquals(expected.getNumberOfColumns(), table.getNumberOfColumns());
    assertEquals(length, table.getRowCount(), "ROW COUNT");
    for (int col = 0; col < expected.getNumberOfColumns(); col++) {
      ColumnVector expect = expected.getColumn(col);
      ColumnVector cv = table.getColumn(col);
      String name = String.valueOf(col);
      if (rowOffset != 0 || length != expected.getRowCount()) {
        name = name + " PART " + rowOffset + "-" + (rowOffset + length - 1);
      }
      assertPartialColumnsAreEqual(expect, rowOffset, length, cv, name, enableNullCheck, enableNullabilityCheck);
    }
  }

  /**
   * Checks and asserts that the two tables match
   * @param expected the expected result table
   * @param table the input table to compare against expected
   */
  public static void assertTablesAreEqual(Table expected, Table table) {
    assertPartialTablesAreEqual(expected, 0, expected.getRowCount(), table, true, false);
  }

  void assertTablesHaveSameValues(HashMap<Object, Integer>[] expectedTable, Table table) {
    assertEquals(expectedTable.length, table.getNumberOfColumns());
    int numCols = table.getNumberOfColumns();
    long numRows = table.getRowCount();
    for (int col = 0; col < numCols; col++) {
      for (long row = 0; row < numRows; row++) {
        try (HostColumnVector cv = table.getColumn(col).copyToHost()) {
          Object key = 0;
          if (cv.getType().equals(DType.INT32)) {
            key = cv.getInt(row);
          } else {
            key = cv.getDouble(row);
          }
          assertTrue(expectedTable[col].containsKey(key));
          Integer count = expectedTable[col].get(key);
          if (count == 1) {
            expectedTable[col].remove(key);
          } else {
            expectedTable[col].put(key, count - 1);
          }
        }
      }
    }
    for (int i = 0 ; i < expectedTable.length ; i++) {
      assertTrue(expectedTable[i].isEmpty());
    }
  }

  public static void assertTableTypes(DType[] expectedTypes, Table t) {
    int len = t.getNumberOfColumns();
    assertEquals(expectedTypes.length, len);
    for (int i = 0; i < len; i++) {
      ColumnVector vec = t.getColumn(i);
      DType type = vec.getType();
      assertEquals(expectedTypes[i], type, "Types don't match at " + i);
    }
  }

  @Test
  void testMergeSimple() {
    try (Table table1 = new Table.TestBuilder()
            .column(5, 3, 3, 1, 1)
            .column(5, 3, null, 1, 2)
            .column(1, 3, 5, 7, 9)
            .build();
         Table table2 = new Table.TestBuilder()
                 .column(1, 2, 7)
                 .column(3, 2, 2)
                 .column(1, 3, 10)
                 .build();
         Table expected = new Table.TestBuilder()
                 .column(1, 1, 1, 2, 3, 3, 5, 7)
                 .column(3, 2, 1, 2, null, 3, 5, 2)
                 .column(1, 9, 7, 3, 5, 3, 1, 10)
                 .build();
         Table sortedTable1 = table1.orderBy(OrderByArg.asc(0), OrderByArg.desc(1));
         Table sortedTable2 = table2.orderBy(OrderByArg.asc(0), OrderByArg.desc(1));
         Table merged = Table.merge(Arrays.asList(sortedTable1, sortedTable2), OrderByArg.asc(0), OrderByArg.desc(1))) {
      assertTablesAreEqual(expected, merged);
    }
  }

  @Test
  void testOrderByAD() {
    try (Table table = new Table.TestBuilder()
        .column(5, 3, 3, 1, 1)
        .column(5, 3, 4, 1, 2)
        .column(1, 3, 5, 7, 9)
        .build();
         Table expected = new Table.TestBuilder()
             .column(1, 1, 3, 3, 5)
             .column(2, 1, 4, 3, 5)
             .column(9, 7, 5, 3, 1)
             .build();
         Table sortedTable = table.orderBy(OrderByArg.asc(0), OrderByArg.desc(1))) {
      assertTablesAreEqual(expected, sortedTable);
    }
  }

  @Test
  void testSortOrderSimple() {
    try (Table table = new Table.TestBuilder()
        .column(5, 3, 3, 1, 1)
        .column(5, 3, 4, 1, 2)
        .column(1, 3, 5, 7, 9)
        .build();
         Table expected = new Table.TestBuilder()
             .column(1, 1, 3, 3, 5)
             .column(2, 1, 4, 3, 5)
             .column(9, 7, 5, 3, 1)
             .build();
         ColumnVector gatherMap = table.sortOrder(OrderByArg.asc(0), OrderByArg.desc(1));
         Table sortedTable = table.gather(gatherMap)) {
      assertTablesAreEqual(expected, sortedTable);
    }
  }

  @Test
  void testOrderByDD() {
    try (Table table = new Table.TestBuilder()
        .column(5, 3, 3, 1, 1)
        .column(5, 3, 4, 1, 2)
        .column(1, 3, 5, 7, 9)
        .build();
         Table expected = new Table.TestBuilder()
             .column(5, 3, 3, 1, 1)
             .column(5, 4, 3, 2, 1)
             .column(1, 5, 3, 9, 7)
             .build();
         Table sortedTable = table.orderBy(OrderByArg.desc(0), OrderByArg.desc(1))) {
      assertTablesAreEqual(expected, sortedTable);
    }
  }

  @Test
  void testOrderByWithNulls() {
    try (Table table = new Table.TestBuilder()
        .column(5, null, 3, 1, 1)
        .column(5, 3, 4, null, null)
        .column("4", "3", "2", "1", "0")
        .column(1, 3, 5, 7, 9)
        .build();
         Table expected = new Table.TestBuilder()
             .column(1, 1, 3, 5, null)
             .column(null, null, 4, 5, 3)
             .column("1", "0", "2", "4", "3")
             .column(7, 9, 5, 1, 3)
             .build();
         Table sortedTable = table.orderBy(OrderByArg.asc(0), OrderByArg.desc(1))) {
      assertTablesAreEqual(expected, sortedTable);
    }
  }

  @Test
  void testOrderByWithNullsAndStrings() {
    try (Table table = new Table.TestBuilder()
        .column("4", "3", "2", "1", "0")
        .column(5, null, 3, 1, 1)
        .column(5, 3, 4, null, null)
        .column(1, 3, 5, 7, 9)
        .build();
         Table expected = new Table.TestBuilder()
             .column("0", "1", "2", "3", "4")
             .column(1, 1, 3, null, 5)
             .column(null, null, 4, 3, 5)
             .column(9, 7, 5, 3, 1)
             .build();
         Table sortedTable = table.orderBy(OrderByArg.asc(0))) {
      assertTablesAreEqual(expected, sortedTable);
    }
  }

  @Test
  void testTableCreationIncreasesRefCount() {
    //tests the Table increases the refcount on column vectors
    assertThrows(IllegalStateException.class, () -> {
      try (ColumnVector v1 = ColumnVector.build(DType.INT32, 5, Range.appendInts(5));
           ColumnVector v2 = ColumnVector.build(DType.INT32, 5, Range.appendInts(5))) {
        assertDoesNotThrow(() -> {
          try (Table t = new Table(new ColumnVector[]{v1, v2})) {
            v1.close();
            v2.close();
          }
        });
      }
    });
  }

  @Test
  void testGetRows() {
    try (ColumnVector v1 = ColumnVector.build(DType.INT32, 5, Range.appendInts(5));
         ColumnVector v2 = ColumnVector.build(DType.INT32, 5, Range.appendInts(5));
         Table t = new Table(new ColumnVector[]{v1, v2})) {
      assertEquals(5, t.getRowCount());
    }
  }

  @Test
  void testSettingNullVectors() {
    ColumnVector[] columnVectors = null;
    assertThrows(AssertionError.class, () -> new Table(columnVectors));
  }

  @Test
  void testAllRowsSize() {
    try (ColumnVector v1 = ColumnVector.build(DType.INT32, 4, Range.appendInts(4));
         ColumnVector v2 = ColumnVector.build(DType.INT32, 5, Range.appendInts(5))) {
      assertThrows(AssertionError.class, () -> {
        try (Table t = new Table(new ColumnVector[]{v1, v2})) {
        }
      });
    }
  }

  @Test
  void testGetNumberOfColumns() {
    try (ColumnVector v1 = ColumnVector.build(DType.INT32, 5, Range.appendInts(5));
         ColumnVector v2 = ColumnVector.build(DType.INT32, 5, Range.appendInts(5));
         Table t = new Table(new ColumnVector[]{v1, v2})) {
      assertEquals(2, t.getNumberOfColumns());
    }
  }

  @Test
  void testReadCSVPrune() {
    Schema schema = Schema.builder()
        .column(DType.INT32, "A")
        .column(DType.FLOAT64, "B")
        .column(DType.INT64, "C")
        .build();
    CSVOptions opts = CSVOptions.builder()
        .includeColumn("A")
        .includeColumn("B")
        .build();
    try (Table expected = new Table.TestBuilder()
        .column(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        .column(110.0, 111.0, 112.0, 113.0, 114.0, 115.0, 116.0, 117.0, 118.2, 119.8)
        .build();
         Table table = Table.readCSV(schema, opts, new File("./src/test/resources/simple.csv"))) {
      assertTablesAreEqual(expected, table);
    }
  }

  @Test
  void testReadCSVBufferInferred() {
    CSVOptions opts = CSVOptions.builder()
        .includeColumn("A")
        .includeColumn("B")
        .hasHeader()
        .withComment('#')
        .build();
    byte[] data = ("A,B,C\n" +
        "0,110.0,120'\n" +
        "#0.5,1.0,200\n" +
        "1,111.0,121\n" +
        "2,112.0,122\n" +
        "3,113.0,123\n" +
        "4,114.0,124\n" +
        "5,115.0,125\n" +
        "6,116.0,126\n" +
        "7,117.0,127\n" +
        "8,118.2,128\n" +
        "9,119.8,129").getBytes(StandardCharsets.UTF_8);
    try (Table expected = new Table.TestBuilder()
        .column(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L)
        .column(110.0, 111.0, 112.0, 113.0, 114.0, 115.0, 116.0, 117.0, 118.2, 119.8)
        .build();
         Table table = Table.readCSV(Schema.INFERRED, opts, data)) {
      assertTablesAreEqual(expected, table);
    }
  }

  @Test
  void testReadCSVBuffer() {
    CSVOptions opts = CSVOptions.builder()
        .includeColumn("A")
        .includeColumn("B")
        .hasHeader()
        .withDelim('|')
        .withQuote('\'')
        .withNullValue("NULL")
        .build();
    try (Table expected = new Table.TestBuilder()
        .column(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        .column(110.0, 111.0, 112.0, 113.0, 114.0, 115.0, 116.0, null, 118.2, 119.8)
        .build();
         Table table = Table.readCSV(TableTest.CSV_DATA_BUFFER_SCHEMA, opts,
             TableTest.CSV_DATA_BUFFER)) {
      assertTablesAreEqual(expected, table);
    }
  }

  @Test
  void testReadCSVWithOffset() {
    CSVOptions opts = CSVOptions.builder()
        .includeColumn("A")
        .includeColumn("B")
        .hasHeader(false)
        .withDelim('|')
        .withNullValue("NULL")
        .build();
    int bytesToIgnore = 24;
    try (Table expected = new Table.TestBuilder()
        .column(1, 2, 3, 4, 5, 6, 7, 8, 9)
        .column(111.0, 112.0, 113.0, 114.0, 115.0, 116.0, null, 118.2, 119.8)
        .build();
         Table table = Table.readCSV(TableTest.CSV_DATA_BUFFER_SCHEMA, opts,
             TableTest.CSV_DATA_BUFFER, bytesToIgnore, CSV_DATA_BUFFER.length - bytesToIgnore)) {
      assertTablesAreEqual(expected, table);
    }
  }

  @Test
  void testReadCSVOtherTypes() {
    final byte[] CSV_DATA_WITH_TYPES = ("A,B,C,D\n" +
        "0,true,120,\"zero\"\n" +
        "1,True,121,\"one\"\n" +
        "2,false,122,\"two\"\n" +
        "3,false,123,\"three\"\n" +
        "4,TRUE,124,\"four\"\n" +
        "5,true,125,\"five\"\n" +
        "6,true,126,\"six\"\n" +
        "7,NULL,127,NULL\n" +
        "8,false,128,\"eight\"\n" +
        "9,false,129,\"nine\uD80C\uDC3F\"").getBytes(StandardCharsets.UTF_8);

    final Schema CSV_DATA_WITH_TYPES_SCHEMA = Schema.builder()
        .column(DType.INT32, "A")
        .column(DType.BOOL8, "B")
        .column(DType.INT64, "C")
        .column(DType.STRING, "D")
        .build();

    CSVOptions opts = CSVOptions.builder()
        .includeColumn("A", "B", "D")
        .hasHeader(true)
        .withNullValue("NULL")
        .withQuote('"')
        .withTrueValue("true", "True", "TRUE")
        .withFalseValue("false")
        .build();
    try (Table expected = new Table.TestBuilder()
        .column(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        .column(true, true, false, false, true, true, true, null, false, false)
        .column("zero", "one", "two", "three", "four", "five", "six", null, "eight", "nine\uD80C\uDC3F")
        .build();
         Table table = Table.readCSV(CSV_DATA_WITH_TYPES_SCHEMA, opts, CSV_DATA_WITH_TYPES)) {
      assertTablesAreEqual(expected, table);
    }
  }

  @Test
  void testReadCSV() {
    Schema schema = Schema.builder()
        .column(DType.INT32, "A")
        .column(DType.FLOAT64, "B")
        .column(DType.INT64, "C")
        .column(DType.STRING, "D")
        .build();
    try (Table expected = new Table.TestBuilder()
        .column(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        .column(110.0, 111.0, 112.0, 113.0, 114.0, 115.0, 116.0, 117.0, 118.2, 119.8)
        .column(120L, 121L, 122L, 123L, 124L, 125L, 126L, 127L, 128L, 129L)
        .column("one", "two", "three", "four", "five", "six", "seven\ud801\uddb8", "eight\uBF68", "nine\u03E8", "ten")
        .build();
         Table table = Table.readCSV(schema, new File("./src/test/resources/simple.csv"))) {
      assertTablesAreEqual(expected, table);
    }
  }

  @Test
  void testReadParquet() {
    ParquetOptions opts = ParquetOptions.builder()
        .includeColumn("loan_id")
        .includeColumn("zip")
        .includeColumn("num_units")
        .build();
    try (Table table = Table.readParquet(opts, TEST_PARQUET_FILE)) {
      long rows = table.getRowCount();
      assertEquals(1000, rows);
      assertTableTypes(new DType[]{DType.INT64, DType.INT32, DType.INT32}, table);
    }
  }

  @Test
  void testReadParquetBuffer() throws IOException {
    ParquetOptions opts = ParquetOptions.builder()
        .includeColumn("loan_id")
        .includeColumn("coborrow_credit_score")
        .includeColumn("borrower_credit_score")
        .build();

    byte[] buffer = new byte[(int) TEST_PARQUET_FILE.length() + 1024];
    int bufferLen = 0;
    try (FileInputStream in = new FileInputStream(TEST_PARQUET_FILE)) {
      bufferLen = in.read(buffer);
    }
    try (Table table = Table.readParquet(opts, buffer, 0, bufferLen)) {
      long rows = table.getRowCount();
      assertEquals(1000, rows);
      assertTableTypes(new DType[]{DType.INT64, DType.FLOAT64, DType.FLOAT64}, table);
    }
  }

  @Test
  void testReadParquetFull() {
    try (Table table = Table.readParquet(TEST_PARQUET_FILE)) {
      long rows = table.getRowCount();
      assertEquals(1000, rows);

      DType[] expectedTypes = new DType[]{
          DType.INT64, // loan_id
          DType.INT32, // orig_channel
          DType.FLOAT64, // orig_interest_rate
          DType.INT32, // orig_upb
          DType.INT32, // orig_loan_term
          DType.TIMESTAMP_DAYS, // orig_date
          DType.TIMESTAMP_DAYS, // first_pay_date
          DType.FLOAT64, // orig_ltv
          DType.FLOAT64, // orig_cltv
          DType.FLOAT64, // num_borrowers
          DType.FLOAT64, // dti
          DType.FLOAT64, // borrower_credit_score
          DType.INT32, // first_home_buyer
          DType.INT32, // loan_purpose
          DType.INT32, // property_type
          DType.INT32, // num_units
          DType.INT32, // occupancy_status
          DType.INT32, // property_state
          DType.INT32, // zip
          DType.FLOAT64, // mortgage_insurance_percent
          DType.INT32, // product_type
          DType.FLOAT64, // coborrow_credit_score
          DType.FLOAT64, // mortgage_insurance_type
          DType.INT32, // relocation_mortgage_indicator
          DType.INT32, // quarter
          DType.INT32 // seller_id
      };

      assertTableTypes(expectedTypes, table);
    }
  }

  @Test
  void testReadParquetContainsDecimalData() {
    try (Table table = Table.readParquet(TEST_DECIMAL_PARQUET_FILE)) {
      long rows = table.getRowCount();
      assertEquals(100, rows);
      DType[] expectedTypes = new DType[]{
          DType.create(DType.DTypeEnum.DECIMAL64, 0), // Decimal(18, 0)
          DType.create(DType.DTypeEnum.DECIMAL32, -3), // Decimal(7, 3)
          DType.create(DType.DTypeEnum.DECIMAL64, -10),  // Decimal(10, 10)
          DType.create(DType.DTypeEnum.DECIMAL32, 0),  // Decimal(1, 0)
          DType.create(DType.DTypeEnum.DECIMAL64, -15),  // Decimal(18, 15)
          DType.FLOAT64,  // Decimal(20, 10) which is backed by FIXED_LEN_BYTE_ARRAY
          DType.INT64,
          DType.FLOAT32
      };
      assertTableTypes(expectedTypes, table);
    }
    // An CudfException will be thrown here because it contains a FIXED_LEN_BYTE_ARRAY column whose type length exceeds 8.
    ParquetOptions opts = ParquetOptions.builder().enableStrictDecimalType(true).build();
    assertThrows(ai.rapids.cudf.CudfException.class, () -> {
      try (Table table = Table.readParquet(opts, TEST_DECIMAL_PARQUET_FILE)) {}
    });
  }

  @Test
  void testReadORC() {
    ORCOptions opts = ORCOptions.builder()
        .includeColumn("string1")
        .includeColumn("float1")
        .includeColumn("int1")
        .build();
    try (Table expected = new Table.TestBuilder()
        .column("hi","bye")
        .column(1.0f,2.0f)
        .column(65536,65536)
        .build();
         Table table = Table.readORC(opts, TEST_ORC_FILE)) {
      assertTablesAreEqual(expected, table);
    }
  }

  @Test
  void testReadORCBuffer() throws IOException {
    ORCOptions opts = ORCOptions.builder()
        .includeColumn("string1")
        .includeColumn("float1")
        .includeColumn("int1")
        .build();

    int bufferLen = 0;
    byte[] buffer = Files.readAllBytes(TEST_ORC_FILE.toPath());
    bufferLen = buffer.length;
    try (Table expected = new Table.TestBuilder()
        .column("hi","bye")
        .column(1.0f,2.0f)
        .column(65536,65536)
        .build();
         Table table = Table.readORC(opts, buffer, 0, bufferLen)) {
      assertTablesAreEqual(expected, table);
    }
  }

  @Test
  void testReadORCFull() {
    try (Table expected = new Table.TestBuilder()
        .column(false, true)
        .column((byte)1, (byte)100)
        .column((short)1024, (short)2048)
        .column(65536, 65536)
        .column(9223372036854775807L,9223372036854775807L)
        .column(1.0f, 2.0f)
        .column(-15.0, -5.0)
        .column("hi", "bye")
        .build();
         Table table = Table.readORC(TEST_ORC_FILE)) {
      assertTablesAreEqual(expected,  table);
    }
  }

  @Test
  void testReadORCNumPyTypes() {
    // by default ORC will promote TIMESTAMP_DAYS to TIMESTAMP_MILLISECONDS
    DType found;
    try (Table table = Table.readORC(TEST_ORC_TIMESTAMP_DATE_FILE)) {
      assertEquals(2, table.getNumberOfColumns());
      found = table.getColumn(0).getType();
      assertTrue(found.isTimestampType());
      assertEquals(DType.TIMESTAMP_MILLISECONDS, table.getColumn(1).getType());
    }

    // specifying no NumPy types should load them as TIMESTAMP_DAYS
    ORCOptions opts = ORCOptions.builder().withNumPyTypes(false).build();
    try (Table table = Table.readORC(opts, TEST_ORC_TIMESTAMP_DATE_FILE)) {
      assertEquals(2, table.getNumberOfColumns());
      assertEquals(found, table.getColumn(0).getType());
      assertEquals(DType.TIMESTAMP_DAYS, table.getColumn(1).getType());
    }
  }

  @Test
  void testReadORCTimeUnit() {
    // specifying no NumPy types should load them as TIMESTAMP_DAYS.
    // specifying a specific type will return the result in that unit
    ORCOptions opts = ORCOptions.builder()
        .withNumPyTypes(false)
        .withTimeUnit(DType.TIMESTAMP_SECONDS)
        .build();
    try (Table table = Table.readORC(opts, TEST_ORC_TIMESTAMP_DATE_FILE)) {
      assertEquals(2, table.getNumberOfColumns());
      assertEquals(DType.TIMESTAMP_SECONDS, table.getColumn(0).getType());
      assertEquals(DType.TIMESTAMP_DAYS, table.getColumn(1).getType());
    }
  }

  @Test
  void testLeftJoinWithNulls() {
    try (Table leftTable = new Table.TestBuilder()
        .column(  2,   3,   9,   0,   1,   7,   4,   6,   5,   8)
        .column(100, 101, 102, 103, 104, 105, 106, 107, 108, 109)
        .build();
         Table rightTable = new Table.TestBuilder()
             .column(  6,   5,   9,   8,  10,  32)
             .column(201, 202, 203, 204, 205, 206)
             .build();
         Table expected = new Table.TestBuilder()
             .column(   2,    3,   9,    0,    1,    7,    4,   6,   5,   8) // common
             .column( 100,  101, 102,  103,  104,  105,  106, 107, 108, 109) // left
             .column(null, null, 203, null, null, null, null, 201, 202, 204) // right
             .build();
         Table joinedTable = leftTable.onColumns(0).leftJoin(rightTable.onColumns(0), true);
         Table orderedJoinedTable = joinedTable.orderBy(OrderByArg.asc(1, true))) {
      assertTablesAreEqual(expected, orderedJoinedTable);
    }
  }

 @Test
  void testLeftJoinOnNullKeys() {
    try (Table leftTable = new Table.TestBuilder()
        .column(  2,   3,   9,   0,   1,   7,   4, null, null,   8)
        .column(100, 101, 102, 103, 104, 105, 106,  107,  108, 109)
        .build();

         Table rightTable = new Table.TestBuilder()
             .column(null, null,   9,   8,  10,  32)
             .column( 201,  202, 203, 204, 205, 206)
             .build()) {

       try (Table expectedResults = new Table.TestBuilder()
           .column(   2,    3,   9,    0,    1,    7,    4, null, null, null, null,   8) // common
           .column( 100,  101, 102,  103,  104,  105,  106,  107,  107,  108,  108, 109) // left
           .column(null, null, 203, null, null, null, null,  201,  202,  201,  202, 204) // right
           .build();

           Table joinedTable = leftTable.onColumns(0).leftJoin(rightTable.onColumns(0));
           Table orderedJoinedTable = joinedTable.orderBy(OrderByArg.asc(1, true))) {
         assertTablesAreEqual(expectedResults, orderedJoinedTable);
       }

       try (Table expectedResults = new Table.TestBuilder()
           .column(   2,    3,   9,    0,    1,    7,    4, null, null,    8) // common
           .column( 100,  101, 102,  103,  104,  105,  106,  107,  108,  109) // left
           .column(null, null, 203, null, null, null, null, null, null,  204) // right
           .build();

           Table joinedTable = leftTable.onColumns(0).leftJoin(rightTable.onColumns(0), false);
           Table orderedJoinedTable = joinedTable.orderBy(OrderByArg.asc(1, true))) {
         assertTablesAreEqual(expectedResults, orderedJoinedTable);
       }
    }
  }

  @Test
  void testLeftJoin() {
    try (Table leftTable = new Table.TestBuilder()
        .column(360, 326, 254, 306, 109, 361, 251, 335, 301, 317)
        .column( 10,  11,  12,  13,  14,  15,  16,  17,  18,  19)
        .build();
         Table rightTable = new Table.TestBuilder()
             .column(306, 301, 360, 109, 335, 254, 317, 361, 251, 326)
             .column( 20,  21,  22,  23,  24,  25,  26,  27,  28,  29)
             .build();
         Table joinedTable = leftTable.onColumns(0).leftJoin(rightTable.onColumns(0), true);
         Table orderedJoinedTable = joinedTable.orderBy(OrderByArg.asc(1, true));
         Table expected = new Table.TestBuilder()
             .column(360, 326, 254, 306, 109, 361, 251, 335, 301, 317) // common
             .column( 10,  11,  12,  13,  14,  15,  16,  17,  18,  19) // left
             .column( 22,  29,  25,  20,  23,  27,  28,  24,  21,  26) // right
             .build()) {
      assertTablesAreEqual(expected, orderedJoinedTable);
    }
  }

  @Test
  void testLeftJoinLeftEmpty() {
    final Integer[] emptyInts = new Integer[0];
    try (Table leftTable = new Table.TestBuilder()
        .column(emptyInts)
        .column(emptyInts)
        .build();
         Table rightTable = new Table.TestBuilder()
             .column(306, 301, 360, 109, 335, 254, 317, 361, 251, 326)
             .column( 20,  21,  22,  23,  24,  25,  26,  27,  28,  29)
             .build();
         Table joinedTable = leftTable.onColumns(0).leftJoin(rightTable.onColumns(0), true);
         Table expected = new Table.TestBuilder()
             .column(emptyInts) // common
             .column(emptyInts) // left
             .column(emptyInts) // right
             .build()) {
      assertTablesAreEqual(expected, joinedTable);
    }
  }

  @Test
  void testLeftJoinRightEmpty() {
    final Integer[] emptyInts = new Integer[0];
    final Integer[] nullInts = new Integer[10];
    Arrays.fill(nullInts, null);
    try (Table leftTable = new Table.TestBuilder()
        .column(360, 326, 254, 306, 109, 361, 251, 335, 301, 317)
        .column( 10,  11,  12,  13,  14,  15,  16,  17,  18,  19)
        .build();
         Table rightTable = new Table.TestBuilder()
             .column(emptyInts)
             .column(emptyInts)
             .build();
         Table joinedTable = leftTable.onColumns(0).leftJoin(rightTable.onColumns(0), true);
         Table orderedJoinedTable = joinedTable.orderBy(OrderByArg.asc(1, true));
         Table expected = new Table.TestBuilder()
             .column(360, 326, 254, 306, 109, 361, 251, 335, 301, 317) // common
             .column( 10,  11,  12,  13,  14,  15,  16,  17,  18,  19) // left
             .column(nullInts) // right
             .build()) {
      assertTablesAreEqual(expected, orderedJoinedTable);
    }
  }

  @Test
  void testFullJoinWithNonCommonKeys() {
    try (Table leftTable = new Table.TestBuilder()
            .column(  2,   3,   9,   0,   1,   7,   4,   6,   5,   8)
            .column(100, 101, 102, 103, 104, 105, 106, 107, 108, 109)
            .build();
         Table rightTable = new Table.TestBuilder()
                 .column(  6,   5,   9,   8,  10,  32)
                 .column(200, 201, 202, 203, 204, 205)
                 .build();
         Table expected = new Table.TestBuilder()
                 .column(   0,    1,    2,    3,    4,   5,   6,    7,   8,   9,   10,   32) // common
                 .column( 103,  104,  100,  101,  106, 108, 107,  105, 109, 102, null, null) // left
                 .column(null, null, null, null, null, 201, 200, null, 203, 202,  204,  205) // right
                 .build();
         Table joinedTable = leftTable.onColumns(0).fullJoin(rightTable.onColumns(0), true);
         Table orderedJoinedTable = joinedTable.orderBy(OrderByArg.asc(0, true))) {
      assertTablesAreEqual(expected, orderedJoinedTable);
    }
  }

  @Test
  void testFullJoinLeftEmpty() {
    final Integer[] emptyInts = new Integer[0];
    final Integer[] nullInts = new Integer[6];
    try (Table leftTable = new Table.TestBuilder().column(emptyInts).column(emptyInts).build();
         Table rightTable = new Table.TestBuilder()
             .column(  6,   5,   9,   8,  10,  32)
             .column(200, 201, 202, 203, 204, 205)
             .build();
         Table expected = new Table.TestBuilder()
             .column(   5,    6,    8,    9,   10,   32) // common
             .column(nullInts) // left
             .column( 201,  200,  203,  202,  204,  205) // right
             .build();
         Table joinedTable = leftTable.onColumns(0).fullJoin(rightTable.onColumns(0), true);
         Table orderedJoinedTable = joinedTable.orderBy(OrderByArg.asc(0, true))) {
      assertTablesAreEqual(expected, orderedJoinedTable);
    }
  }

  @Test
  void testFullJoinRightEmpty() {
    final Integer[] emptyInts = new Integer[0];
    final Integer[] nullInts = new Integer[10];
    try (Table leftTable = new Table.TestBuilder()
        .column(  2,   3,   9,   0,   1,   7,   4,   6,   5,   8)
        .column(100, 101, 102, 103, 104, 105, 106, 107, 108, 109)
        .build();
         Table rightTable = new Table.TestBuilder().column(emptyInts).column(emptyInts).build();
         Table expected = new Table.TestBuilder()
             .column(   0,    1,    2,    3,    4,   5,   6,    7,   8,   9) // common
             .column( 103,  104,  100,  101,  106, 108, 107,  105, 109, 102) // left
             .column(nullInts) // right
             .build();
         Table joinedTable = leftTable.onColumns(0).fullJoin(rightTable.onColumns(0), true);
         Table orderedJoinedTable = joinedTable.orderBy(OrderByArg.asc(0, true))) {
      assertTablesAreEqual(expected, orderedJoinedTable);
    }
  }

  @Test
  void testFullJoinOnNullKeys() {
    try (Table leftTable = new Table.TestBuilder()
            .column(  2,   3, null,   0,   1,   7,   4, null,   5,   8)
            .column(100, 101,  102, 103, 104, 105, 106,  107, 108, 109)
            .build();
         Table rightTable = new Table.TestBuilder()
                 .column(null,   5, null,   8,  10,  32)
                 .column( 200, 201,  202, 203, 204, 205)
                 .build()) {

      // First, test that null-key rows match, with compareNullsEqual=true.
      try (Table expectedResults = new Table.TestBuilder()
              .column(null, null, null, null,    0,    1,    2,    3,    4,   5,    7,   8,   10,   32) // common
              .column( 102,  102,  107,  107,  103,  104,  100,  101,  106, 108,  105, 109, null, null) // left
              .column( 200,  202,  200,  202, null, null, null, null, null, 201, null, 203,  204,  205) // right
              .build();
           Table joinedTable = leftTable.onColumns(0).fullJoin(rightTable.onColumns(0));
           Table orderedJoinedTable = joinedTable.orderBy(OrderByArg.asc(0, true), OrderByArg.asc(1, true))) {
        assertTablesAreEqual(expectedResults, orderedJoinedTable);
      }

      // Next, test that null-key rows do not match, with compareNullsEqual=false.
      try (Table expectedResults = new Table.TestBuilder()
              .column(null, null, null, null,    0,    1,    2,    3,    4,   5,    7,   8,   10,   32) // common
              .column(null, null,  102,  107,  103,  104,  100,  101,  106, 108,  105, 109, null, null) // left
              .column( 200,  202, null, null, null, null, null, null, null, 201, null, 203,  204,  205) // right
              .build();
           Table joinedTable = leftTable.onColumns(0).fullJoin(rightTable.onColumns(0), false);
           Table orderedJoinedTable = joinedTable.orderBy(
               OrderByArg.asc(0, true), OrderByArg.asc(1, true), OrderByArg.asc(2, true))) {
        assertTablesAreEqual(expectedResults, orderedJoinedTable);
      }
    }
  }

  @Test
  void testFullJoinWithOnlyCommonKeys() {
    try (Table leftTable = new Table.TestBuilder()
            .column(360, 326, 254, 306, 109, 361, 251, 335, 301, 317)
            .column(100, 101, 102, 103, 104, 105, 106, 107, 108, 109)
            .build();
         Table rightTable = new Table.TestBuilder()
                 .column(306, 301, 360, 109, 335, 254, 317, 361, 251, 326)
                 .column(200, 201, 202, 203, 204, 205, 206, 207, 208, 209)
                 .build();
         Table joinedTable = leftTable.onColumns(0).fullJoin(rightTable.onColumns(new int[]{0}), true);
         Table orderedJoinedTable = joinedTable.orderBy(OrderByArg.asc(1, true));
         Table expected = new Table.TestBuilder()
                 .column(360, 326, 254, 306, 109, 361, 251, 335, 301, 317) // common
                 .column(100, 101, 102, 103, 104, 105, 106, 107, 108, 109) // left
                 .column(202, 209, 205, 200, 203, 207, 208, 204, 201, 206) // right
                 .build()) {
      assertTablesAreEqual(expected, orderedJoinedTable);
    }
  }

  @Test
  void testInnerJoinWithNonCommonKeys() {
    try (Table leftTable = new Table.TestBuilder()
        .column(  2,   3,   9,   0,   1,   7,   4,   6,   5,   8)
        .column(100, 101, 102, 103, 104, 105, 106, 107, 108, 109)
        .build();
         Table rightTable = new Table.TestBuilder()
             .column(  6,   5,   9,   8,  10,  32)
             .column(200, 201, 202, 203, 204, 205)
             .build();
         Table expected = new Table.TestBuilder()
             .column(  9,   6,   5,   8) // common
             .column(102, 107, 108, 109) // left
             .column(202, 200, 201, 203) // right
             .build();
         Table joinedTable = leftTable.onColumns(0).innerJoin(rightTable.onColumns(0), true);
         Table orderedJoinedTable = joinedTable.orderBy(OrderByArg.asc(1, true))) {
      assertTablesAreEqual(expected, orderedJoinedTable);
    }
  }

  @Test
  void testInnerJoinLeftEmpty() {
    final Integer[] emptyInts = new Integer[0];
    try (Table leftTable = new Table.TestBuilder()
        .column(  2,   3,   9,   0,   1,   7,   4,   6,   5,   8)
        .column(100, 101, 102, 103, 104, 105, 106, 107, 108, 109)
        .build();
         Table rightTable = new Table.TestBuilder().column(emptyInts).column(emptyInts).build();
         Table expected = new Table.TestBuilder()
             .column(emptyInts).column(emptyInts).column(emptyInts).build();
         Table joinedTable = leftTable.onColumns(0).innerJoin(rightTable.onColumns(0), true)) {
      assertTablesAreEqual(expected, joinedTable);
    }
  }

  @Test
  void testInnerJoinRightEmpty() {
    final Integer[] emptyInts = new Integer[0];
    try (Table leftTable = new Table.TestBuilder()
        .column(  2,   3,   9,   0,   1,   7,   4,   6,   5,   8)
        .column(100, 101, 102, 103, 104, 105, 106, 107, 108, 109)
        .build();
         Table rightTable = new Table.TestBuilder().column(emptyInts).column(emptyInts).build();
         Table expected = new Table.TestBuilder()
             .column(emptyInts).column(emptyInts).column(emptyInts).build();
         Table joinedTable = leftTable.onColumns(0).innerJoin(rightTable.onColumns(0), true)) {
      assertTablesAreEqual(expected, joinedTable);
    }
  }

  @Test
  void testInnerJoinOnNullKeys() {
    try (Table leftTable = new Table.TestBuilder()
             .column(  2,   3,   9,   0,   1,   7,   4,   6, null,   8)
             .column(100, 101, 102, 103, 104, 105, 106, 107,  108, 109)
             .build();
         Table rightTable = new Table.TestBuilder()
             .column(  6, null,   9,   8,  10,  32)
             .column(200,  201, 202, 203, 204, 205)
             .build()) {

      // First, test that null-key rows match, with compareNullsEqual=true.
      try (Table expected = new Table.TestBuilder()
             .column(  9,   6, null,   8) // common
             .column(102, 107,  108, 109) // left
             .column(202, 200,  201, 203) // right
             .build();
         Table joinedTable = leftTable.onColumns(0).innerJoin(rightTable.onColumns(0));
         Table orderedJoinedTable = joinedTable.orderBy(OrderByArg.asc(1, true))) {
        assertTablesAreEqual(expected, orderedJoinedTable);
      }

      // Next, test that null-key rows do not match, with compareNullsEqual=false.
      try (Table expected = new Table.TestBuilder()
              .column(  9,   6,    8) // common
              .column(102, 107,  109) // left
              .column(202, 200,  203) // right
              .build();
           Table joinedTable = leftTable.onColumns(0).innerJoin(rightTable.onColumns(0), false);
           Table orderedJoinedTable = joinedTable.orderBy(OrderByArg.asc(1, true))){
        assertTablesAreEqual(expected, orderedJoinedTable);
      }
    }
  }

  @Test
  void testInnerJoinWithOnlyCommonKeys() {
    try (Table leftTable = new Table.TestBuilder()
        .column(360, 326, 254, 306, 109, 361, 251, 335, 301, 317)
        .column(100, 101, 102, 103, 104, 105, 106, 107, 108, 109)
        .build();
         Table rightTable = new Table.TestBuilder()
             .column(306, 301, 360, 109, 335, 254, 317, 361, 251, 326)
             .column(200, 201, 202, 203, 204, 205, 206, 207, 208, 209)
             .build();
         Table joinedTable = leftTable.onColumns(0).innerJoin(rightTable.onColumns(new int[]{0}), true);
         Table orderedJoinedTable = joinedTable.orderBy(OrderByArg.asc(1, true));
         Table expected = new Table.TestBuilder()
             .column(360, 326, 254, 306, 109, 361, 251, 335, 301, 317) // common
             .column(100, 101, 102, 103, 104, 105, 106, 107, 108, 109) // left
             .column(202, 209, 205, 200, 203, 207, 208, 204, 201, 206) // right
             .build()) {
      assertTablesAreEqual(expected, orderedJoinedTable);
    }
  }

  @Test
  void testLeftSemiJoin() {
    try (Table leftTable = new Table.TestBuilder()
        .column(  2,   3,   9,   0,   1,   7,   4,   6,   5,   8)
        .column(100, 101, 102, 103, 104, 105, 106, 107, 108, 109)
        .build();
         Table rightTable = new Table.TestBuilder()
             .column(  6,   5,   9,   8,  10,  32)
             .column(201, 202, 203, 204, 205, 206)
             .build();
         Table expected = new Table.TestBuilder()
             .column(  9,   6,   5,   8)
             .column(102, 107, 108, 109)
             .build();
         Table joinedTable = leftTable.onColumns(0).leftSemiJoin(rightTable.onColumns(0), true);
         Table orderedJoinedTable = joinedTable.orderBy(OrderByArg.asc(1, true))) {
      assertTablesAreEqual(expected, orderedJoinedTable);
    }
  }

  @Test
  void testLeftSemiJoinLeftEmpty() {
    final Integer[] emptyInts = new Integer[0];
    try (Table leftTable = new Table.TestBuilder().column(emptyInts).column(emptyInts).build();
         Table rightTable = new Table.TestBuilder()
             .column(  6,   5,   9,   8,  10,  32)
             .column(201, 202, 203, 204, 205, 206)
             .build();
         Table joinedTable = leftTable.onColumns(0).leftSemiJoin(rightTable.onColumns(0), true)) {
      assertTablesAreEqual(leftTable, joinedTable);
    }
  }

  @Test
  void testLeftSemiJoinRightEmpty() {
    final Integer[] emptyInts = new Integer[0];
    try (Table leftTable = new Table.TestBuilder()
        .column(  2,   3,   9,   0,   1,   7,   4,   6,   5,   8)
        .column(100, 101, 102, 103, 104, 105, 106, 107, 108, 109)
        .build();
         Table rightTable = new Table.TestBuilder().column(emptyInts).column(emptyInts).build();
         Table joinedTable = leftTable.onColumns(0).leftSemiJoin(rightTable.onColumns(0), true)) {
      assertTablesAreEqual(rightTable, joinedTable);
    }
  }

  @Test
  void testLeftSemiJoinWithNulls() {
    try (Table leftTable = new Table.TestBuilder()
        .column( 360,  326, null,  306, null,  254,  251,  361,  301,  317)
        .column(  10,   11, null,   13,   14, null,   16,   17,   18,   19)
        .column("20", "29", "22", "23", "24", "25", "26", "27", "28", "29")
        .build();
         Table rightTable = new Table.TestBuilder()
             .column( 306,  301,  360,  109,  335,  254,  317,  361,  251,  326)
             .column("20", "21", "22", "23", "24", "25", "26", "27", "28", "29")
             .build();
         Table joinedTable = leftTable.onColumns(0, 2).leftSemiJoin(rightTable.onColumns(0, 1), true);
         Table orderedJoinedTable = joinedTable.orderBy(OrderByArg.asc(0, true));
         Table expected = new Table.TestBuilder()
             .column(254,   326,   361)
             .column(null,   11,    17)
             .column("25", "29",  "27")
             .build()) {
      assertTablesAreEqual(expected, orderedJoinedTable);
    }
  }

  @Test
  void testLeftSemiJoinOnNullKeys() {
    try (Table leftTable = new Table.TestBuilder()
            .column(  2,   3,   9,   0,   1,   7,   4,   6, null,   8)
            .column(100, 101, 102, 103, 104, 105, 106, 107,  108, 109)
            .build();
         Table rightTable = new Table.TestBuilder()
                 .column(  6, null,   9,   8,  10,  32)
                 .column(201,  202, 203, 204, 205, 206)
                 .build()) {

       // First, test that null-key rows match, with compareNullsEqual=true.
       try (Table expected = new Table.TestBuilder()
               .column(  9,   6, null,   8)
               .column(102, 107,  108, 109)
               .build();
            Table joinedTable = leftTable.onColumns(0).leftSemiJoin(rightTable.onColumns(0));
            Table orderedJoinedTable = joinedTable.orderBy(OrderByArg.asc(1, true))) {
          assertTablesAreEqual(expected, orderedJoinedTable);
       }

      // Next, test that null-key rows do not match, with compareNullsEqual=false.
      try (Table expected = new Table.TestBuilder()
              .column(  9,   6,   8)
              .column(102, 107, 109)
              .build();
           Table joinedTable = leftTable.onColumns(0).leftSemiJoin(rightTable.onColumns(0), false);
           Table orderedJoinedTable = joinedTable.orderBy(OrderByArg.asc(1, true))) {
        assertTablesAreEqual(expected, orderedJoinedTable);
      }
    }
  }

  @Test
  void testLeftAntiJoin() {
    try (Table leftTable = new Table.TestBuilder()
        .column(  2,   3,   9,   0,   1,   7,   4,   6,   5,   8)
        .column(100, 101, 102, 103, 104, 105, 106, 107, 108, 109)
        .build();
         Table rightTable = new Table.TestBuilder()
             .column(  6,   5,   9,   8,  10,  32)
             .column(201, 202, 203, 204, 205, 206)
             .build();
         Table expected = new Table.TestBuilder()
             .column(  2,   3,   0,   1,   7,   4)
             .column(100, 101, 103, 104, 105, 106)
             .build();
         Table joinedTable = leftTable.onColumns(0).leftAntiJoin(rightTable.onColumns(0), true);
         Table orderedJoinedTable = joinedTable.orderBy(OrderByArg.asc(1, true))) {
      assertTablesAreEqual(expected, orderedJoinedTable);
    }
  }

  @Test
  void testLeftAntiJoinLeftEmpty() {
    final Integer[] emptyInts = new Integer[0];
    try (Table leftTable = new Table.TestBuilder().column(emptyInts).column(emptyInts).build();
         Table rightTable = new Table.TestBuilder()
             .column(  6,   5,   9,   8,  10,  32)
             .column(201, 202, 203, 204, 205, 206)
             .build();
         Table joinedTable = leftTable.onColumns(0).leftAntiJoin(rightTable.onColumns(0), true)) {
      assertTablesAreEqual(leftTable, joinedTable);
    }
  }

  @Test
  void testLeftAntiJoinRightEmpty() {
    final Integer[] emptyInts = new Integer[0];
    try (Table leftTable = new Table.TestBuilder()
        .column(  2,   3,   9,   0,   1,   7,   4,   6,   5,   8)
        .column(100, 101, 102, 103, 104, 105, 106, 107, 108, 109)
        .build();
         Table rightTable = new Table.TestBuilder().column(emptyInts).column(emptyInts).build();
         Table joinedTable = leftTable.onColumns(0).leftAntiJoin(rightTable.onColumns(0), true)) {
      assertTablesAreEqual(leftTable, joinedTable);
    }
  }

  @Test
  void testLeftAntiJoinOnNullKeys() {
    try (Table leftTable = new Table.TestBuilder()
            .column(  2,   3,   9,   0,   1,   7,   4,   6, null,   8)
            .column(100, 101, 102, 103, 104, 105, 106, 107,  108, 109)
            .build();
         Table rightTable = new Table.TestBuilder()
                 .column(  6, null,   9,   8,  10,  32)
                 .column(201,  202, 203, 204, 205, 206)
                 .build()) {

      // First, test that null-key rows match, with compareNullsEqual=true.
      try (Table expected = new Table.TestBuilder()
              .column(  2,   3,   0,   1,   7,   4)
              .column(100, 101, 103, 104, 105, 106)
              .build();
           Table joinedTable = leftTable.onColumns(0).leftAntiJoin(rightTable.onColumns(0));
           Table orderedJoinedTable = joinedTable.orderBy(OrderByArg.asc(1, true))) {
        assertTablesAreEqual(expected, orderedJoinedTable);
      }

      // Next, test that null-key rows do not match, with compareNullsEqual=false.
      try (Table expected = new Table.TestBuilder()
              .column(  2,   3,   0,   1,   7,   4, null)
              .column(100, 101, 103, 104, 105, 106,  108)
              .build();
           Table joinedTable = leftTable.onColumns(0).leftAntiJoin(rightTable.onColumns(0), false);
           Table orderedJoinedTable = joinedTable.orderBy(OrderByArg.asc(1, true))) {
        assertTablesAreEqual(expected, orderedJoinedTable);
      }
    }
  }

  @Test
  void testLeftAntiJoinWithNulls() {
    try (Table leftTable = new Table.TestBuilder()
        .column( 360,  326, null,  306, null,  254,  251,  361,  301,  317)
        .column(  10,   11, null,   13,   14, null,   16,   17,   18,   19)
        .column("20", "21", "22", "23", "24", "25", "26", "27", "28", "29")
        .build();
         Table rightTable = new Table.TestBuilder()
             .column( 306,  301,  360,  109,  335,  254,  317,  361,  251,  326)
             .column("20", "21", "22", "23", "24", "25", "26", "27", "28", "29")
             .build();
         Table joinedTable = leftTable.onColumns(0, 2).leftAntiJoin(rightTable.onColumns(0, 1), true);
         Table orderedJoinedTable = joinedTable.orderBy(OrderByArg.asc(2, true));
         Table expected = new Table.TestBuilder()
             .column( 360,  326, null,  306, null,  251,  301,  317)
             .column(  10,   11, null,   13,   14,   16,   18,   19)
             .column("20", "21", "22", "23", "24", "26", "28", "29")
             .build()) {
      assertTablesAreEqual(expected, orderedJoinedTable);
    }
  }

  @Test
  void testCrossJoin() {
    try (Table leftTable = new Table.TestBuilder()
            .column(100, 101, 102)
            .build();
         Table rightTable = new Table.TestBuilder()
                 .column(200, null)
                 .build();
         Table expected = new Table.TestBuilder()
                 .column(  100, 100,  101, 101,  102, 102) // left
                 .column( null, 200, null, 200, null, 200) // right
                 .build();
         Table joinedTable = leftTable.crossJoin(rightTable);
         Table orderedJoinedTable =
                 joinedTable.orderBy(
                     OrderByArg.asc(0, true),
                     OrderByArg.asc(1, true))) {
      assertTablesAreEqual(expected, orderedJoinedTable);
    }
  }

  private void verifyJoinGatherMaps(GatherMap[] maps, Table expected) {
    assertEquals(2, maps.length);
    int numRows = (int) expected.getRowCount();
    assertEquals(numRows, maps[0].getRowCount());
    assertEquals(numRows, maps[1].getRowCount());
    try (ColumnVector leftMap = maps[0].toColumnView(0, numRows).copyToColumnVector();
         ColumnVector rightMap = maps[1].toColumnView(0, numRows).copyToColumnVector();
         Table result = new Table(leftMap, rightMap);
         Table orderedResult = result.orderBy(OrderByArg.asc(0, true))) {
      assertTablesAreEqual(expected, orderedResult);
    }
  }

  private void verifySemiJoinGatherMap(GatherMap map, Table expected) {
    int numRows = (int) expected.getRowCount();
    assertEquals(numRows, map.getRowCount());
    try (ColumnVector leftMap = map.toColumnView(0, numRows).copyToColumnVector();
         Table result = new Table(leftMap);
         Table orderedResult = result.orderBy(OrderByArg.asc(0, true))) {
      assertTablesAreEqual(expected, orderedResult);
    }
  }

  @Test
  void testLeftJoinGatherMaps() {
    final int inv = Integer.MIN_VALUE;
    try (Table leftKeys = new Table.TestBuilder().column(2, 3, 9, 0, 1, 7, 4, 6, 5, 8).build();
         Table rightKeys = new Table.TestBuilder().column(6, 5, 9, 8, 10, 32).build();
         Table expected = new Table.TestBuilder()
             .column(  0,   1, 2,   3,   4,   5,   6, 7, 8, 9)
             .column(inv, inv, 2, inv, inv, inv, inv, 0, 1, 3)
             .build()) {
      GatherMap[] maps = leftKeys.leftJoinGatherMaps(rightKeys, false);
      try {
        verifyJoinGatherMaps(maps, expected);
      } finally {
        for (GatherMap map : maps) {
          map.close();
        }
      }
    }
  }

  @Test
  void testLeftJoinGatherMapsNulls() {
    final int inv = Integer.MIN_VALUE;
    try (Table leftKeys = new Table.TestBuilder()
            .column(2, 3, 9, 0, 1, 7, 4, null, null, 8)
            .build();
         Table rightKeys = new Table.TestBuilder()
             .column(null, null, 9, 8, 10, 32)
             .build();
         Table expected = new Table.TestBuilder()
             .column(  0,   1, 2,   3,   4,   5,   6, 7, 7, 8, 8, 9) // left
             .column(inv, inv, 2, inv, inv, inv, inv, 0, 1, 0, 1, 3) // right
             .build()) {
      GatherMap[] maps = leftKeys.leftJoinGatherMaps(rightKeys, true);
      try {
        verifyJoinGatherMaps(maps, expected);
      } finally {
        for (GatherMap map : maps) {
          map.close();
        }
      }
    }
  }

  @Test
  void testLeftHashJoinGatherMaps() {
    final int inv = Integer.MIN_VALUE;
    try (Table leftKeys = new Table.TestBuilder().column(2, 3, 9, 0, 1, 7, 4, 6, 5, 8).build();
         Table rightKeys = new Table.TestBuilder().column(6, 5, 9, 8, 10, 32).build();
         HashJoin rightHash = new HashJoin(rightKeys, false);
         Table expected = new Table.TestBuilder()
             .column(  0,   1, 2,   3,   4,   5,   6, 7, 8, 9)
             .column(inv, inv, 2, inv, inv, inv, inv, 0, 1, 3)
             .build()) {
      GatherMap[] maps = leftKeys.leftJoinGatherMaps(rightHash);
      try {
        verifyJoinGatherMaps(maps, expected);
      } finally {
        for (GatherMap map : maps) {
          map.close();
        }
      }
    }
  }

  @Test
  void testLeftHashJoinGatherMapsWithCount() {
    final int inv = Integer.MIN_VALUE;
    try (Table leftKeys = new Table.TestBuilder().column(2, 3, 9, 0, 1, 7, 4, 6, 5, 8).build();
         Table rightKeys = new Table.TestBuilder().column(6, 5, 9, 8, 10, 32).build();
         HashJoin rightHash = new HashJoin(rightKeys, false);
         Table expected = new Table.TestBuilder()
             .column(  0,   1, 2,   3,   4,   5,   6, 7, 8, 9)
             .column(inv, inv, 2, inv, inv, inv, inv, 0, 1, 3)
             .build()) {
      long rowCount = leftKeys.leftJoinRowCount(rightHash);
      assertEquals(expected.getRowCount(), rowCount);
      GatherMap[] maps = leftKeys.leftJoinGatherMaps(rightHash, rowCount);
      try {
        verifyJoinGatherMaps(maps, expected);
      } finally {
        for (GatherMap map : maps) {
          map.close();
        }
      }
    }
  }

  @Test
  void testLeftHashJoinGatherMapsNulls() {
    final int inv = Integer.MIN_VALUE;
    try (Table leftKeys = new Table.TestBuilder()
        .column(2, 3, 9, 0, 1, 7, 4, null, null, 8)
        .build();
         Table rightKeys = new Table.TestBuilder()
             .column(null, null, 9, 8, 10, 32)
             .build();
         HashJoin rightHash = new HashJoin(rightKeys, true);
         Table expected = new Table.TestBuilder()
             .column(  0,   1, 2,   3,   4,   5,   6, 7, 7, 8, 8, 9) // left
             .column(inv, inv, 2, inv, inv, inv, inv, 0, 1, 0, 1, 3) // right
             .build()) {
      GatherMap[] maps = leftKeys.leftJoinGatherMaps(rightHash);
      try {
        verifyJoinGatherMaps(maps, expected);
      } finally {
        for (GatherMap map : maps) {
          map.close();
        }
      }
    }
  }

  @Test
  void testLeftHashJoinGatherMapsNullsWithCount() {
    final int inv = Integer.MIN_VALUE;
    try (Table leftKeys = new Table.TestBuilder()
        .column(2, 3, 9, 0, 1, 7, 4, null, null, 8)
        .build();
         Table rightKeys = new Table.TestBuilder()
             .column(null, null, 9, 8, 10, 32)
             .build();
         HashJoin rightHash = new HashJoin(rightKeys,true);
         Table expected = new Table.TestBuilder()
             .column(  0,   1, 2,   3,   4,   5,   6, 7, 7, 8, 8, 9) // left
             .column(inv, inv, 2, inv, inv, inv, inv, 0, 1, 0, 1, 3) // right
             .build()) {
      long rowCount = leftKeys.leftJoinRowCount(rightHash);
      assertEquals(expected.getRowCount(), rowCount);
      GatherMap[] maps = leftKeys.leftJoinGatherMaps(rightHash, rowCount);
      try {
        verifyJoinGatherMaps(maps, expected);
      } finally {
        for (GatherMap map : maps) {
          map.close();
        }
      }
    }
  }

  @Test
  void testConditionalLeftJoinGatherMaps() {
    final int inv = Integer.MIN_VALUE;
    BinaryOperation expr = new BinaryOperation(BinaryOperator.GREATER,
        new ColumnReference(0, TableReference.LEFT),
        new ColumnReference(0, TableReference.RIGHT));
    try (Table left = new Table.TestBuilder().column(2, 3, 9, 0, 1, 7, 4, 6, 5, 8).build();
         Table right = new Table.TestBuilder()
             .column(6, 5, 9, 8, 10, 32)
             .column(0, 1, 2, 3, 4, 5).build();
         Table expected = new Table.TestBuilder()
             .column(  0,   1, 2, 2, 2,   3,   4, 5, 5,   6, 7,   8, 9, 9)
             .column(inv, inv, 0, 1, 3, inv, inv, 0, 1, inv, 1, inv, 0, 1)
             .build();
         CompiledExpression condition = expr.compile()) {
      GatherMap[] maps = left.conditionalLeftJoinGatherMaps(right, condition);
      try {
        verifyJoinGatherMaps(maps, expected);
      } finally {
        for (GatherMap map : maps) {
          map.close();
        }
      }
    }
  }

  @Test
  void testConditionalLeftJoinGatherMapsNulls() {
    final int inv = Integer.MIN_VALUE;
    BinaryOperation expr = new BinaryOperation(BinaryOperator.NULL_EQUAL,
        new ColumnReference(0, TableReference.LEFT),
        new ColumnReference(0, TableReference.RIGHT));
    try (Table left = new Table.TestBuilder()
        .column(2, 3, 9, 0, 1, 7, 4, null, null, 8)
        .build();
         Table right = new Table.TestBuilder()
             .column(null, null, 9, 8, 10, 32)
             .build();
         Table expected = new Table.TestBuilder()
             .column(  0,   1, 2,   3,   4,   5,   6, 7, 7, 8, 8, 9) // left
             .column(inv, inv, 2, inv, inv, inv, inv, 0, 1, 0, 1, 3) // right
             .build();
         CompiledExpression condition = expr.compile()) {
      GatherMap[] maps = left.conditionalLeftJoinGatherMaps(right, condition);
      try {
        verifyJoinGatherMaps(maps, expected);
      } finally {
        for (GatherMap map : maps) {
          map.close();
        }
      }
    }
  }

  @Test
  void testConditionalLeftJoinGatherMapsWithCount() {
    final int inv = Integer.MIN_VALUE;
    BinaryOperation expr = new BinaryOperation(BinaryOperator.GREATER,
        new ColumnReference(0, TableReference.LEFT),
        new ColumnReference(0, TableReference.RIGHT));
    try (Table left = new Table.TestBuilder().column(2, 3, 9, 0, 1, 7, 4, 6, 5, 8).build();
         Table right = new Table.TestBuilder()
             .column(6, 5, 9, 8, 10, 32)
             .column(0, 1, 2, 3, 4, 5).build();
         Table expected = new Table.TestBuilder()
             .column(  0,   1, 2, 2, 2,   3,   4, 5, 5,   6, 7,   8, 9, 9)
             .column(inv, inv, 0, 1, 3, inv, inv, 0, 1, inv, 1, inv, 0, 1)
             .build();
         CompiledExpression condition = expr.compile()) {
      long rowCount = left.conditionalLeftJoinRowCount(right, condition);
      assertEquals(expected.getRowCount(), rowCount);
      GatherMap[] maps = left.conditionalLeftJoinGatherMaps(right, condition, rowCount);
      try {
        verifyJoinGatherMaps(maps, expected);
      } finally {
        for (GatherMap map : maps) {
          map.close();
        }
      }
    }
  }

  @Test
  void testConditionalLeftJoinGatherMapsNullsWithCount() {
    final int inv = Integer.MIN_VALUE;
    BinaryOperation expr = new BinaryOperation(BinaryOperator.NULL_EQUAL,
        new ColumnReference(0, TableReference.LEFT),
        new ColumnReference(0, TableReference.RIGHT));
    try (Table left = new Table.TestBuilder()
        .column(2, 3, 9, 0, 1, 7, 4, null, null, 8)
        .build();
         Table right = new Table.TestBuilder()
             .column(null, null, 9, 8, 10, 32)
             .build();
         Table expected = new Table.TestBuilder()
             .column(  0,   1, 2,   3,   4,   5,   6, 7, 7, 8, 8, 9) // left
             .column(inv, inv, 2, inv, inv, inv, inv, 0, 1, 0, 1, 3) // right
             .build();
         CompiledExpression condition = expr.compile()) {
      long rowCount = left.conditionalLeftJoinRowCount(right, condition);
      assertEquals(expected.getRowCount(), rowCount);
      GatherMap[] maps = left.conditionalLeftJoinGatherMaps(right, condition, rowCount);
      try {
        verifyJoinGatherMaps(maps, expected);
      } finally {
        for (GatherMap map : maps) {
          map.close();
        }
      }
    }
  }

  @Test
  void testInnerJoinGatherMaps() {
    try (Table leftKeys = new Table.TestBuilder().column(2, 3, 9, 0, 1, 7, 4, 6, 5, 8).build();
         Table rightKeys = new Table.TestBuilder().column(6, 5, 9, 8, 10, 32).build();
         Table expected = new Table.TestBuilder()
             .column(2, 7, 8, 9) // left
             .column(2, 0, 1, 3) // right
             .build()) {
      GatherMap[] maps = leftKeys.innerJoinGatherMaps(rightKeys, false);
      try {
        verifyJoinGatherMaps(maps, expected);
      } finally {
        for (GatherMap map : maps) {
          map.close();
        }
      }
    }
  }

  @Test
  void testInnerJoinGatherMapsNulls() {
    try (Table leftKeys = new Table.TestBuilder()
        .column(2, 3, 9, 0, 1, 7, 4, null, null, 8)
        .build();
         Table rightKeys = new Table.TestBuilder()
             .column(null, null, 9, 8, 10, 32)
             .build();
         Table expected = new Table.TestBuilder()
             .column(2, 7, 7, 8, 8, 9) // left
             .column(2, 0, 1, 0, 1, 3) // right
             .build()) {
      GatherMap[] maps = leftKeys.innerJoinGatherMaps(rightKeys, true);
      try {
        verifyJoinGatherMaps(maps, expected);
      } finally {
        for (GatherMap map : maps) {
          map.close();
        }
      }
    }
  }

  @Test
  void testInnerHashJoinGatherMaps() {
    try (Table leftKeys = new Table.TestBuilder().column(2, 3, 9, 0, 1, 7, 4, 6, 5, 8).build();
         Table rightKeys = new Table.TestBuilder().column(6, 5, 9, 8, 10, 32).build();
         HashJoin rightHash = new HashJoin(rightKeys, false);
         Table expected = new Table.TestBuilder()
             .column(2, 7, 8, 9) // left
             .column(2, 0, 1, 3) // right
             .build()) {
      GatherMap[] maps = leftKeys.innerJoinGatherMaps(rightHash);
      try {
        verifyJoinGatherMaps(maps, expected);
      } finally {
        for (GatherMap map : maps) {
          map.close();
        }
      }
    }
  }

  @Test
  void testInnerHashJoinGatherMapsWithCount() {
    try (Table leftKeys = new Table.TestBuilder().column(2, 3, 9, 0, 1, 7, 4, 6, 5, 8).build();
         Table rightKeys = new Table.TestBuilder().column(6, 5, 9, 8, 10, 32).build();
         HashJoin rightHash = new HashJoin(rightKeys, false);
         Table expected = new Table.TestBuilder()
             .column(2, 7, 8, 9) // left
             .column(2, 0, 1, 3) // right
             .build()) {
      long rowCount = leftKeys.innerJoinRowCount(rightHash);
      assertEquals(expected.getRowCount(), rowCount);
      GatherMap[] maps = leftKeys.innerJoinGatherMaps(rightHash, rowCount);
      try {
        verifyJoinGatherMaps(maps, expected);
      } finally {
        for (GatherMap map : maps) {
          map.close();
        }
      }
    }
  }

  @Test
  void testInnerHashJoinGatherMapsNulls() {
    try (Table leftKeys = new Table.TestBuilder()
        .column(2, 3, 9, 0, 1, 7, 4, null, null, 8)
        .build();
         Table rightKeys = new Table.TestBuilder()
             .column(null, null, 9, 8, 10, 32)
             .build();
         HashJoin rightHash = new HashJoin(rightKeys, true);
         Table expected = new Table.TestBuilder()
             .column(2, 7, 7, 8, 8, 9) // left
             .column(2, 0, 1, 0, 1, 3) // right
             .build()) {
      GatherMap[] maps = leftKeys.innerJoinGatherMaps(rightHash);
      try {
        verifyJoinGatherMaps(maps, expected);
      } finally {
        for (GatherMap map : maps) {
          map.close();
        }
      }
    }
  }

  @Test
  void testInnerHashJoinGatherMapsNullsWithCount() {
    try (Table leftKeys = new Table.TestBuilder()
        .column(2, 3, 9, 0, 1, 7, 4, null, null, 8)
        .build();
         Table rightKeys = new Table.TestBuilder()
             .column(null, null, 9, 8, 10, 32)
             .build();
         HashJoin rightHash = new HashJoin(rightKeys, true);
         Table expected = new Table.TestBuilder()
             .column(2, 7, 7, 8, 8, 9) // left
             .column(2, 0, 1, 0, 1, 3) // right
             .build()) {
      long rowCount = leftKeys.innerJoinRowCount(rightHash);
      assertEquals(expected.getRowCount(), rowCount);
      GatherMap[] maps = leftKeys.innerJoinGatherMaps(rightHash, rowCount);
      try {
        verifyJoinGatherMaps(maps, expected);
      } finally {
        for (GatherMap map : maps) {
          map.close();
        }
      }
    }
  }

  @Test
  void testConditionalInnerJoinGatherMaps() {
    BinaryOperation expr = new BinaryOperation(BinaryOperator.GREATER,
        new ColumnReference(0, TableReference.LEFT),
        new ColumnReference(0, TableReference.RIGHT));
    try (Table left = new Table.TestBuilder().column(2, 3, 9, 0, 1, 7, 4, 6, 5, 8).build();
         Table right = new Table.TestBuilder()
             .column(6, 5, 9, 8, 10, 32)
             .column(0, 1, 2, 3, 4, 5).build();
         Table expected = new Table.TestBuilder()
             .column(2, 2, 2, 5, 5, 7, 9, 9)
             .column(0, 1, 3, 0, 1, 1, 0, 1)
             .build();
         CompiledExpression condition = expr.compile()) {
      GatherMap[] maps = left.conditionalInnerJoinGatherMaps(right, condition);
      try {
        verifyJoinGatherMaps(maps, expected);
      } finally {
        for (GatherMap map : maps) {
          map.close();
        }
      }
    }
  }

  // Test non-null-supporting equality at least once.
  @Test
  void testConditionalInnerJoinGatherMapsEqual() {
    BinaryOperation expr = new BinaryOperation(BinaryOperator.EQUAL,
        new ColumnReference(0, TableReference.LEFT),
        new ColumnReference(0, TableReference.RIGHT));
    try (Table left = new Table.TestBuilder()
        .column(2, 3, 9, 0, 1, 7, 4, null, null, 8)
        .build();
         Table right = new Table.TestBuilder()
             .column(null, null, 9, 8, 10, 32)
             .build();
         Table expected = new Table.TestBuilder()
             .column(2, 9) // left
             .column(2, 3) // right
             .build();
         CompiledExpression condition = expr.compile()) {
      GatherMap[] maps = left.conditionalInnerJoinGatherMaps(right, condition);
      try {
        verifyJoinGatherMaps(maps, expected);
      } finally {
        for (GatherMap map : maps) {
          map.close();
        }
      }
    }
  }

  @Test
  void testConditionalInnerJoinGatherMapsNulls() {
    BinaryOperation expr = new BinaryOperation(BinaryOperator.NULL_EQUAL,
        new ColumnReference(0, TableReference.LEFT),
        new ColumnReference(0, TableReference.RIGHT));
    try (Table left = new Table.TestBuilder()
        .column(2, 3, 9, 0, 1, 7, 4, null, null, 8)
        .build();
         Table right = new Table.TestBuilder()
             .column(null, null, 9, 8, 10, 32)
             .build();
         Table expected = new Table.TestBuilder()
             .column(2, 7, 7, 8, 8, 9) // left
             .column(2, 0, 1, 0, 1, 3) // right
             .build();
         CompiledExpression condition = expr.compile()) {
      GatherMap[] maps = left.conditionalInnerJoinGatherMaps(right, condition);
      try {
        verifyJoinGatherMaps(maps, expected);
      } finally {
        for (GatherMap map : maps) {
          map.close();
        }
      }
    }
  }

  @Test
  void testConditionalInnerJoinGatherMapsWithCount() {
    BinaryOperation expr = new BinaryOperation(BinaryOperator.GREATER,
        new ColumnReference(0, TableReference.LEFT),
        new ColumnReference(0, TableReference.RIGHT));
    try (Table left = new Table.TestBuilder().column(2, 3, 9, 0, 1, 7, 4, 6, 5, 8).build();
         Table right = new Table.TestBuilder()
             .column(6, 5, 9, 8, 10, 32)
             .column(0, 1, 2, 3, 4, 5).build();
         Table expected = new Table.TestBuilder()
             .column(2, 2, 2, 5, 5, 7, 9, 9)
             .column(0, 1, 3, 0, 1, 1, 0, 1)
             .build();
         CompiledExpression condition = expr.compile()) {
      long rowCount = left.conditionalInnerJoinRowCount(right, condition);
      assertEquals(expected.getRowCount(), rowCount);
      GatherMap[] maps = left.conditionalInnerJoinGatherMaps(right, condition, rowCount);
      try {
        verifyJoinGatherMaps(maps, expected);
      } finally {
        for (GatherMap map : maps) {
          map.close();
        }
      }
    }
  }

  @Test
  void testConditionalInnerJoinGatherMapsNullsWithCount() {
    BinaryOperation expr = new BinaryOperation(BinaryOperator.NULL_EQUAL,
        new ColumnReference(0, TableReference.LEFT),
        new ColumnReference(0, TableReference.RIGHT));
    try (Table left = new Table.TestBuilder()
        .column(2, 3, 9, 0, 1, 7, 4, null, null, 8)
        .build();
         Table right = new Table.TestBuilder()
             .column(null, null, 9, 8, 10, 32)
             .build();
         Table expected = new Table.TestBuilder()
             .column(2, 7, 7, 8, 8, 9) // left
             .column(2, 0, 1, 0, 1, 3) // right
             .build();
         CompiledExpression condition = expr.compile()) {
      long rowCount = left.conditionalInnerJoinRowCount(right, condition);
      assertEquals(expected.getRowCount(), rowCount);
      GatherMap[] maps = left.conditionalInnerJoinGatherMaps(right, condition, rowCount);
      try {
        verifyJoinGatherMaps(maps, expected);
      } finally {
        for (GatherMap map : maps) {
          map.close();
        }
      }
    }
  }

  @Test
  void testFullJoinGatherMaps() {
    final int inv = Integer.MIN_VALUE;
    try (Table leftKeys = new Table.TestBuilder().column(2, 3, 9, null, 1, 7, 4, 6, 5, 8).build();
         Table rightKeys = new Table.TestBuilder().column(6, 5, 9, 8, 10, null).build();
         Table expected = new Table.TestBuilder()
             .column(inv, inv,   0,   1, 2,   3,   4,   5,   6, 7, 8, 9) // left
             .column(  4,   5, inv, inv, 2, inv, inv, inv, inv, 0, 1, 3) // right
             .build()) {
      GatherMap[] maps = leftKeys.fullJoinGatherMaps(rightKeys, false);
      try {
        verifyJoinGatherMaps(maps, expected);
      } finally {
        for (GatherMap map : maps) {
          map.close();
        }
      }
    }
  }

  @Test
  void testFullJoinGatherMapsNulls() {
    final int inv = Integer.MIN_VALUE;
    try (Table leftKeys = new Table.TestBuilder()
             .column(2, 3, 9, 0, 1, 7, 4, null, null, 8)
             .build();
         Table rightKeys = new Table.TestBuilder()
             .column(null, null, 9, 8, 10, 32)
             .build();
         Table expected = new Table.TestBuilder()
             .column(inv, inv,   0,   1, 2,   3,   4,   5,   6, 7, 7, 8, 8, 9) // left
             .column(  4,   5, inv, inv, 2, inv, inv, inv, inv, 0, 1, 0, 1, 3) // right
             .build()) {
      GatherMap[] maps = leftKeys.fullJoinGatherMaps(rightKeys, true);
      try {
        verifyJoinGatherMaps(maps, expected);
      } finally {
        for (GatherMap map : maps) {
          map.close();
        }
      }
    }
  }

  @Test
  void testFullHashJoinGatherMaps() {
    final int inv = Integer.MIN_VALUE;
    try (Table leftKeys = new Table.TestBuilder().column(2, 3, 9, null, 1, 7, 4, 6, 5, 8).build();
         Table rightKeys = new Table.TestBuilder().column(6, 5, 9, 8, 10, null).build();
         HashJoin rightHash = new HashJoin(rightKeys, false);
         Table expected = new Table.TestBuilder()
             .column(inv, inv,   0,   1, 2,   3,   4,   5,   6, 7, 8, 9) // left
             .column(  4,   5, inv, inv, 2, inv, inv, inv, inv, 0, 1, 3) // right
             .build()) {
      GatherMap[] maps = leftKeys.fullJoinGatherMaps(rightHash);
      try {
        verifyJoinGatherMaps(maps, expected);
      } finally {
        for (GatherMap map : maps) {
          map.close();
        }
      }
    }
  }

  @Test
  void testFullHashJoinGatherMapsWithCount() {
    final int inv = Integer.MIN_VALUE;
    try (Table leftKeys = new Table.TestBuilder().column(2, 3, 9, null, 1, 7, 4, 6, 5, 8).build();
         Table rightKeys = new Table.TestBuilder().column(6, 5, 9, 8, 10, null).build();
         HashJoin rightHash = new HashJoin(rightKeys, false);
         Table expected = new Table.TestBuilder()
             .column(inv, inv,   0,   1, 2,   3,   4,   5,   6, 7, 8, 9) // left
             .column(  4,   5, inv, inv, 2, inv, inv, inv, inv, 0, 1, 3) // right
             .build()) {
      long rowCount = leftKeys.fullJoinRowCount(rightHash);
      assertEquals(expected.getRowCount(), rowCount);
      GatherMap[] maps = leftKeys.fullJoinGatherMaps(rightHash, rowCount);
      try {
        verifyJoinGatherMaps(maps, expected);
      } finally {
        for (GatherMap map : maps) {
          map.close();
        }
      }
    }
  }

  @Test
  void testFullHashJoinGatherMapsNulls() {
    final int inv = Integer.MIN_VALUE;
    try (Table leftKeys = new Table.TestBuilder()
        .column(2, 3, 9, 0, 1, 7, 4, null, null, 8)
        .build();
         Table rightKeys = new Table.TestBuilder()
             .column(null, null, 9, 8, 10, 32)
             .build();
         HashJoin rightHash = new HashJoin(rightKeys, true);
         Table expected = new Table.TestBuilder()
             .column(inv, inv,   0,   1, 2,   3,   4,   5,   6, 7, 7, 8, 8, 9) // left
             .column(  4,   5, inv, inv, 2, inv, inv, inv, inv, 0, 1, 0, 1, 3) // right
             .build()) {
      GatherMap[] maps = leftKeys.fullJoinGatherMaps(rightHash);
      try {
        verifyJoinGatherMaps(maps, expected);
      } finally {
        for (GatherMap map : maps) {
          map.close();
        }
      }
    }
  }

  @Test
  void testFullHashJoinGatherMapsNullsWithCount() {
    final int inv = Integer.MIN_VALUE;
    try (Table leftKeys = new Table.TestBuilder()
        .column(2, 3, 9, 0, 1, 7, 4, null, null, 8)
        .build();
         Table rightKeys = new Table.TestBuilder()
             .column(null, null, 9, 8, 10, 32)
             .build();
         HashJoin rightHash = new HashJoin(rightKeys, true);
         Table expected = new Table.TestBuilder()
             .column(inv, inv,   0,   1, 2,   3,   4,   5,   6, 7, 7, 8, 8, 9) // left
             .column(  4,   5, inv, inv, 2, inv, inv, inv, inv, 0, 1, 0, 1, 3) // right
             .build()) {
      long rowCount = leftKeys.fullJoinRowCount(rightHash);
      assertEquals(expected.getRowCount(), rowCount);
      GatherMap[] maps = leftKeys.fullJoinGatherMaps(rightHash, rowCount);
      try {
        verifyJoinGatherMaps(maps, expected);
      } finally {
        for (GatherMap map : maps) {
          map.close();
        }
      }
    }
  }

  @Test
  void testConditionalFullJoinGatherMaps() {
    final int inv = Integer.MIN_VALUE;
    BinaryOperation expr = new BinaryOperation(BinaryOperator.GREATER,
        new ColumnReference(0, TableReference.LEFT),
        new ColumnReference(0, TableReference.RIGHT));
    try (Table left = new Table.TestBuilder().column(2, 3, 9, 0, 1, 7, 4, 6, 5, 8).build();
         Table right = new Table.TestBuilder()
             .column(6, 5, 9, 8, 10, 32)
             .column(0, 1, 2, 3, 4, 5).build();
         Table expected = new Table.TestBuilder()
             .column(inv, inv, inv,   0,   1, 2, 2, 2,   3,   4, 5, 5,   6, 7,   8, 9, 9)
             .column(  2,   4,   5, inv, inv, 0, 1, 3, inv, inv, 0, 1, inv, 1, inv, 0, 1)
             .build();
         CompiledExpression condition = expr.compile()) {
      GatherMap[] maps = left.conditionalFullJoinGatherMaps(right, condition);
      try {
        verifyJoinGatherMaps(maps, expected);
      } finally {
        for (GatherMap map : maps) {
          map.close();
        }
      }
    }
  }

  @Test
  void testConditionalFullJoinGatherMapsNulls() {
    final int inv = Integer.MIN_VALUE;
    BinaryOperation expr = new BinaryOperation(BinaryOperator.NULL_EQUAL,
        new ColumnReference(0, TableReference.LEFT),
        new ColumnReference(0, TableReference.RIGHT));
    try (Table left = new Table.TestBuilder()
        .column(2, 3, 9, 0, 1, 7, 4, null, null, 8)
        .build();
         Table right = new Table.TestBuilder()
             .column(null, null, 9, 8, 10, 32)
             .build();
         Table expected = new Table.TestBuilder()
             .column(inv, inv,   0,   1, 2,   3,   4,   5,   6, 7, 7, 8, 8, 9) // left
             .column(  4,   5, inv, inv, 2, inv, inv, inv, inv, 0, 1, 0, 1, 3) // right
             .build();
         CompiledExpression condition = expr.compile()) {
      GatherMap[] maps = left.conditionalFullJoinGatherMaps(right, condition);
      try {
        verifyJoinGatherMaps(maps, expected);
      } finally {
        for (GatherMap map : maps) {
          map.close();
        }
      }
    }
  }

  @Test
  void testLeftSemiJoinGatherMap() {
    try (Table leftKeys = new Table.TestBuilder().column(2, 3, 9, 0, 1, 7, 4, 6, 5, 8).build();
         Table rightKeys = new Table.TestBuilder().column(6, 5, 9, 8, 10, 32).build();
         Table expected = new Table.TestBuilder()
             .column(2, 7, 8, 9) // left
             .build();
         GatherMap map = leftKeys.leftSemiJoinGatherMap(rightKeys, false)) {
      verifySemiJoinGatherMap(map, expected);
    }
  }

  @Test
  void testLeftSemiJoinGatherMapNulls() {
    try (Table leftKeys = new Table.TestBuilder()
        .column(2, 3, 9, 0, 1, 7, 4, null, null, 8)
        .build();
         Table rightKeys = new Table.TestBuilder()
             .column(null, null, 9, 8, 10, 32)
             .build();
         Table expected = new Table.TestBuilder()
             .column(2, 7, 8, 9) // left
             .build();
         GatherMap map = leftKeys.leftSemiJoinGatherMap(rightKeys, true)) {
      verifySemiJoinGatherMap(map, expected);
    }
  }

  @Test
  void testConditionalLeftSemiJoinGatherMap() {
    BinaryOperation expr = new BinaryOperation(BinaryOperator.GREATER,
        new ColumnReference(0, TableReference.LEFT),
        new ColumnReference(0, TableReference.RIGHT));
    try (Table left = new Table.TestBuilder().column(2, 3, 9, 0, 1, 7, 4, 6, 5, 8).build();
         Table right = new Table.TestBuilder()
             .column(6, 5, 9, 8, 10, 32)
             .column(0, 1, 2, 3, 4, 5).build();
         Table expected = new Table.TestBuilder()
             .column(2, 5, 7, 9) // left
             .build();
         CompiledExpression condition = expr.compile();
         GatherMap map = left.conditionalLeftSemiJoinGatherMap(right, condition)) {
      verifySemiJoinGatherMap(map, expected);
    }
  }

  @Test
  void testConditionalLeftSemiJoinGatherMapNulls() {
    BinaryOperation expr = new BinaryOperation(BinaryOperator.NULL_EQUAL,
        new ColumnReference(0, TableReference.LEFT),
        new ColumnReference(0, TableReference.RIGHT));
    try (Table left = new Table.TestBuilder()
        .column(2, 3, 9, 0, 1, 7, 4, null, null, 8)
        .build();
         Table right = new Table.TestBuilder()
             .column(null, null, 9, 8, 10, 32)
             .build();
         Table expected = new Table.TestBuilder()
             .column(2, 7, 8, 9) // left
             .build();
         CompiledExpression condition = expr.compile();
         GatherMap map = left.conditionalLeftSemiJoinGatherMap(right, condition)) {
      verifySemiJoinGatherMap(map, expected);
    }
  }

  @Test
  void testConditionalLeftSemiJoinGatherMapWithCount() {
    BinaryOperation expr = new BinaryOperation(BinaryOperator.GREATER,
        new ColumnReference(0, TableReference.LEFT),
        new ColumnReference(0, TableReference.RIGHT));
    try (Table left = new Table.TestBuilder().column(2, 3, 9, 0, 1, 7, 4, 6, 5, 8).build();
         Table right = new Table.TestBuilder()
             .column(6, 5, 9, 8, 10, 32)
             .column(0, 1, 2, 3, 4, 5).build();
         Table expected = new Table.TestBuilder()
             .column(2, 5, 7, 9) // left
             .build();
         CompiledExpression condition = expr.compile()) {
      long rowCount = left.conditionalLeftSemiJoinRowCount(right, condition);
      assertEquals(expected.getRowCount(), rowCount);
      try (GatherMap map =
               left.conditionalLeftSemiJoinGatherMap(right, condition, rowCount)) {
        verifySemiJoinGatherMap(map, expected);
      }
    }
  }

  @Test
  void testConditionalLeftSemiJoinGatherMapNullsWithCount() {
    BinaryOperation expr = new BinaryOperation(BinaryOperator.NULL_EQUAL,
        new ColumnReference(0, TableReference.LEFT),
        new ColumnReference(0, TableReference.RIGHT));
    try (Table left = new Table.TestBuilder()
        .column(2, 3, 9, 0, 1, 7, 4, null, null, 8)
        .build();
         Table right = new Table.TestBuilder()
             .column(null, null, 9, 8, 10, 32)
             .build();
         Table expected = new Table.TestBuilder()
             .column(2, 7, 8, 9) // left
             .build();
         CompiledExpression condition = expr.compile()) {
      long rowCount = left.conditionalLeftSemiJoinRowCount(right, condition);
      assertEquals(expected.getRowCount(), rowCount);
      try (GatherMap map =
               left.conditionalLeftSemiJoinGatherMap(right, condition, rowCount)) {
        verifySemiJoinGatherMap(map, expected);
      }
    }
  }

  @Test
  void testAntiSemiJoinGatherMap() {
    try (Table leftKeys = new Table.TestBuilder().column(2, 3, 9, 0, 1, 7, 4, 6, 5, 8).build();
         Table rightKeys = new Table.TestBuilder().column(6, 5, 9, 8, 10, 32).build();
         Table expected = new Table.TestBuilder()
             .column(0, 1, 3, 4, 5, 6) // left
             .build();
         GatherMap map = leftKeys.leftAntiJoinGatherMap(rightKeys, false)) {
      verifySemiJoinGatherMap(map, expected);
    }
  }

  @Test
  void testAntiSemiJoinGatherMapNulls() {
    try (Table leftKeys = new Table.TestBuilder()
        .column(2, 3, 9, 0, 1, 7, 4, null, null, 8)
        .build();
         Table rightKeys = new Table.TestBuilder()
             .column(null, null, 9, 8, 10, 32)
             .build();
         Table expected = new Table.TestBuilder()
             .column(0, 1, 3, 4, 5, 6) // left
             .build();
         GatherMap map = leftKeys.leftAntiJoinGatherMap(rightKeys, true)) {
      verifySemiJoinGatherMap(map, expected);
    }
  }

  @Test
  void testConditionalLeftAntiJoinGatherMap() {
    BinaryOperation expr = new BinaryOperation(BinaryOperator.GREATER,
        new ColumnReference(0, TableReference.LEFT),
        new ColumnReference(0, TableReference.RIGHT));
    try (Table left = new Table.TestBuilder().column(2, 3, 9, 0, 1, 7, 4, 6, 5, 8).build();
         Table right = new Table.TestBuilder()
             .column(6, 5, 9, 8, 10, 32)
             .column(0, 1, 2, 3, 4, 5).build();
         Table expected = new Table.TestBuilder()
             .column(0, 1, 3, 4, 6, 8) // left
             .build();
         CompiledExpression condition = expr.compile();
         GatherMap map = left.conditionalLeftAntiJoinGatherMap(right, condition)) {
      verifySemiJoinGatherMap(map, expected);
    }
  }

  @Test
  void testConditionalAntiSemiJoinGatherMapNulls() {
    BinaryOperation expr = new BinaryOperation(BinaryOperator.NULL_EQUAL,
        new ColumnReference(0, TableReference.LEFT),
        new ColumnReference(0, TableReference.RIGHT));
    try (Table left = new Table.TestBuilder()
        .column(2, 3, 9, 0, 1, 7, 4, null, null, 8)
        .build();
         Table right = new Table.TestBuilder()
             .column(null, null, 9, 8, 10, 32)
             .build();
         Table expected = new Table.TestBuilder()
             .column(0, 1, 3, 4, 5, 6) // left
             .build();
         CompiledExpression condition = expr.compile();
         GatherMap map = left.conditionalLeftAntiJoinGatherMap(right, condition)) {
      verifySemiJoinGatherMap(map, expected);
    }
  }

  @Test
  void testConditionalLeftAntiJoinGatherMapWithCount() {
    BinaryOperation expr = new BinaryOperation(BinaryOperator.GREATER,
        new ColumnReference(0, TableReference.LEFT),
        new ColumnReference(0, TableReference.RIGHT));
    try (Table left = new Table.TestBuilder().column(2, 3, 9, 0, 1, 7, 4, 6, 5, 8).build();
         Table right = new Table.TestBuilder()
             .column(6, 5, 9, 8, 10, 32)
             .column(0, 1, 2, 3, 4, 5).build();
         Table expected = new Table.TestBuilder()
             .column(0, 1, 3, 4, 6, 8) // left
             .build();
         CompiledExpression condition = expr.compile()) {
      long rowCount = left.conditionalLeftAntiJoinRowCount(right, condition);
      assertEquals(expected.getRowCount(), rowCount);
      try (GatherMap map =
               left.conditionalLeftAntiJoinGatherMap(right, condition, rowCount)) {
        verifySemiJoinGatherMap(map, expected);
      }
    }
  }

  @Test
  void testConditionalAntiSemiJoinGatherMapNullsWithCount() {
    BinaryOperation expr = new BinaryOperation(BinaryOperator.NULL_EQUAL,
        new ColumnReference(0, TableReference.LEFT),
        new ColumnReference(0, TableReference.RIGHT));
    try (Table left = new Table.TestBuilder()
        .column(2, 3, 9, 0, 1, 7, 4, null, null, 8)
        .build();
         Table right = new Table.TestBuilder()
             .column(null, null, 9, 8, 10, 32)
             .build();
         Table expected = new Table.TestBuilder()
             .column(0, 1, 3, 4, 5, 6) // left
             .build();
         CompiledExpression condition = expr.compile()) {
      long rowCount = left.conditionalLeftAntiJoinRowCount(right, condition);
      assertEquals(expected.getRowCount(), rowCount);
      try (GatherMap map =
               left.conditionalLeftAntiJoinGatherMap(right, condition, rowCount)) {
        verifySemiJoinGatherMap(map, expected);
      }
    }
  }

  @Test
  void testBoundsNulls() {
    boolean[] descFlags = new boolean[1];
    try (Table table = new TestBuilder()
            .column(null, 20, 20, 20, 30)
            .build();
        Table values = new TestBuilder()
            .column(15)
            .build();
         ColumnVector expected = ColumnVector.fromBoxedInts(1)) {
      try (ColumnVector columnVector = getBoundsCv(descFlags, true, table, values)) {
        assertColumnsAreEqual(expected, columnVector);
      }
      try (ColumnVector columnVector = getBoundsCv(descFlags, false, table, values)) {
        assertColumnsAreEqual(expected, columnVector);
      }
    }
  }

  @Test
  void testBoundsValuesSizeBigger() {
    boolean[] descFlags = new boolean[2];
    try(Table table = new TestBuilder()
            .column(90, 100, 120, 130, 135)
            .column(.5, .5, .5, .7, .7)
            .build();
        Table values = new TestBuilder()
            .column(120)
            .column(.3)
            .column(.7)
            .build()) {
      assertThrows(CudfException.class, () -> getBoundsCv(descFlags, true, table, values));
      assertThrows(CudfException.class, () ->  getBoundsCv(descFlags, false, table, values));
    }
  }

  @Test
  void testBoundsInputSizeBigger() {
    boolean[] descFlags = new boolean[3];
    try(Table table = new TestBuilder()
            .column(90, 100, 120, 130, 135)
            .column(.5, .5, .5, .7, .7)
            .column(90, 100, 120, 130, 135)
            .build();
        Table values = new TestBuilder()
            .column(120)
            .column(.3)
            .build()) {
      assertThrows(CudfException.class, () -> getBoundsCv(descFlags, true, table, values));
      assertThrows(CudfException.class, () -> getBoundsCv(descFlags, false, table, values));
    }
  }

  @Test
  void testBoundsMultiCol() {
    boolean[] descFlags = new boolean[4];
    try (Table table = new TestBuilder()
            .column(10, 20, 20, 20, 20)
            .column(5.0, .5, .5, .7, .7)
            .column("1","2","3","4","4")
            .column(90, 77, 78, 61, 61)
            .build();
        Table values = new TestBuilder()
            .column(20)
            .column(0.7)
            .column("4")
            .column(61)
            .build()) {
      try (ColumnVector columnVector = getBoundsCv(descFlags, true, table, values);
           ColumnVector expected = ColumnVector.fromBoxedInts(5)) {
        assertColumnsAreEqual(expected, columnVector);
      }
      try (ColumnVector columnVector = getBoundsCv(descFlags, false, table, values);
           ColumnVector expected = ColumnVector.fromBoxedInts(3)) {
        assertColumnsAreEqual(expected, columnVector);
      }
    }
  }

  @Test
  void testBoundsFloatsMultiVal() {
    boolean[] descFlags = new boolean[1];
    try (Table table = new TestBuilder()
            .column(10.0, 20.6, 20.7)
            .build();
        Table values = new TestBuilder()
            .column(20.3, 20.8)
            .build();
         ColumnVector expected = ColumnVector.fromBoxedInts(1, 3)) {
      try (ColumnVector columnVector = getBoundsCv(descFlags, true, table, values)) {
        assertColumnsAreEqual(expected, columnVector);
      }
      try (ColumnVector columnVector = getBoundsCv(descFlags, false, table, values)) {
        assertColumnsAreEqual(expected, columnVector);
      }
    }
  }

  @Test
  void testBoundsFloatsSingleCol() {
    boolean[] descFlags = {false};
    try(Table table = new TestBuilder()
            .column(10.0, 20.6, 20.7)
            .build();
        Table values = new TestBuilder()
            .column(20.6)
            .build()) {
      try (ColumnVector columnVector = getBoundsCv(descFlags, true, table, values);
           ColumnVector expected = ColumnVector.fromBoxedInts(2)) {
        assertColumnsAreEqual(expected, columnVector);
      }
      try (ColumnVector columnVector = getBoundsCv(descFlags, false, table, values);
           ColumnVector expected = ColumnVector.fromBoxedInts(1)) {
        assertColumnsAreEqual(expected, columnVector);
      }
    }
  }

  @Test
  void testBoundsFloatsSingleColDesc() {
    boolean[] descFlags = new boolean[] {true};
    try(Table table = new TestBuilder()
        .column(20.7, 20.6, 10.0)
        .build();
        Table values = new TestBuilder()
            .column(20.6)
            .build()) {
      try (ColumnVector columnVector = getBoundsCv(descFlags, true, table, values);
           ColumnVector expected = ColumnVector.fromBoxedInts(2)) {
        assertColumnsAreEqual(expected, columnVector);
      }
      try (ColumnVector columnVector = getBoundsCv(descFlags, false, table, values);
           ColumnVector expected = ColumnVector.fromBoxedInts(1)) {
        assertColumnsAreEqual(expected, columnVector);
      }
    }
  }

  @Test
  void testBoundsIntsSingleCol() {
    boolean[] descFlags = new boolean[1];
    try(Table table = new TestBuilder()
            .column(10, 20, 20, 20, 20)
            .build();
        Table values = new TestBuilder()
            .column(20)
            .build()) {
      try (ColumnVector columnVector = getBoundsCv(descFlags, true, table, values);
           ColumnVector expected = ColumnVector.fromBoxedInts(5)) {
        assertColumnsAreEqual(expected, columnVector);
      }
      try (ColumnVector columnVector = getBoundsCv(descFlags, false, table, values);
           ColumnVector expected = ColumnVector.fromBoxedInts(1)) {
        assertColumnsAreEqual(expected, columnVector);
      }
    }
  }

  @Test
  void testBoundsIntsSingleColDesc() {
    boolean[] descFlags = new boolean[]{true};
    try (Table table = new TestBuilder()
        .column(20, 20, 20, 20, 10)
        .build();
         Table values = new TestBuilder()
             .column(5)
             .build();
         ColumnVector expected = ColumnVector.fromBoxedInts(5)) {
      try (ColumnVector columnVector = getBoundsCv(descFlags, true, table, values)) {
        assertColumnsAreEqual(expected, columnVector);
      }
      try (ColumnVector columnVector = getBoundsCv(descFlags, false, table, values)) {
        assertColumnsAreEqual(expected, columnVector);
      }
    }
  }

  @Test
  void testBoundsString() {
    boolean[] descFlags = new boolean[1];
    try (ColumnVector cIn = ColumnVector.build(DType.STRING, 4, (b) -> {
           for (int i = 0; i < 4; i++) {
             b.appendUTF8String(String.valueOf(i).getBytes());
           }
        });
        Table table = new Table(cIn);
        ColumnVector cVal = ColumnVector.fromStrings("0");
        Table values = new Table(cVal)) {
      try (ColumnVector cv = getBoundsCv(descFlags, true, table, values);
           ColumnVector expected = ColumnVector.fromInts(1)) {
        assertColumnsAreEqual(expected, cv);
      }
      try (ColumnVector cv = getBoundsCv(descFlags, false, table, values);
           ColumnVector expected = ColumnVector.fromInts(0)) {
        assertColumnsAreEqual(expected, cv);
      }
    }
  }

  @Test
  void testBoundsEmptyValues() {
    boolean[] descFlags = new boolean[1];
    try (ColumnVector cv = ColumnVector.fromBoxedLongs();
         Table table = new TestBuilder()
             .column(10, 20, 20, 20, 20)
             .build();
         Table values = new Table(cv)) {
      assertThrows(AssertionError.class,
          () -> getBoundsCv(descFlags, true, table, values).close());
      assertThrows(AssertionError.class,
          () -> getBoundsCv(descFlags, false, table, values).close());
    }
  }

  @Test
  void testBoundsEmptyInput() {
    boolean[] descFlags = new boolean[1];
    try (ColumnVector cv =  ColumnVector.fromBoxedLongs();
         Table table = new Table(cv);
         Table values = new TestBuilder()
             .column(20)
             .build()) {
      assertThrows(AssertionError.class,
          () -> getBoundsCv(descFlags, true, table, values).close());
      assertThrows(AssertionError.class,
          () -> getBoundsCv(descFlags, false, table, values).close());
    }
  }

  private ColumnVector getBoundsCv(boolean[] descFlags, boolean isUpperBound,
      Table table, Table values) {
    boolean[] nullsAreSmallest = new boolean[descFlags.length];
    Arrays.fill(nullsAreSmallest, true);
    return isUpperBound ?
        table.upperBound(nullsAreSmallest, values, descFlags) :
        table.lowerBound(nullsAreSmallest, values, descFlags);
  }

  @Test
  void testRepeat() {
    try (Table t = new Table.TestBuilder()
            .column(1, 2)
            .column("a", "b")
            .decimal32Column(-3, 12, -25)
            .decimal64Column(2, 11111L, -22222L)
            .build();
         Table expected = new Table.TestBuilder()
                 .column(1, 1, 1, 2, 2, 2)
                 .column("a", "a", "a", "b", "b", "b")
                 .decimal32Column(-3, 12, 12, 12, -25, -25, -25)
                 .decimal64Column(2, 11111L, 11111L, 11111L, -22222L, -22222L, -22222L)
                 .build();
         Table repeated = t.repeat(3)) {
      assertTablesAreEqual(expected, repeated);
    }
  }

  @Test
  void testRepeatColumn() {
    try (Table t = new Table.TestBuilder()
            .column(1, 2)
            .column("a", "b")
            .decimal32Column(-3, 12, -25)
            .decimal64Column(2, 11111L, -22222L)
            .build();
         ColumnVector repeats = ColumnVector.fromBytes((byte)1, (byte)4);
         Table expected = new Table.TestBuilder()
                 .column(1, 2, 2, 2, 2)
                 .column("a", "b", "b", "b", "b")
                 .decimal32Column(-3, 12, -25, -25, -25, -25)
                 .decimal64Column(2, 11111L, -22222L, -22222L, -22222L, -22222L)
                 .build();
         Table repeated = t.repeat(repeats)) {
      assertTablesAreEqual(expected, repeated);
    }
  }

  @Test
  void testRepeatColumnBad() {
    try (Table t = new Table.TestBuilder()
            .column(1, 2)
            .column("a", "b")
            .build();
         ColumnVector repeats = ColumnVector.fromBytes((byte)2, (byte)-1)) {
      assertThrows(CudfException.class, () -> t.repeat(repeats));
    }
  }

  @Test
  void testInterleaveIntColumns() {
    try (Table t = new Table.TestBuilder()
          .column(1,2,3,4,5)
          .column(6,7,8,9,10)
          .build();
         ColumnVector expected = ColumnVector.fromInts(1,6,2,7,3,8,4,9,5,10);
         ColumnVector actual = t.interleaveColumns()) {
      assertColumnsAreEqual(expected, actual);
    }
  }

  @Test
  void testInterleaveFloatColumns() {
    try (Table t = new Table.TestBuilder()
        .column(1f,2f,3f,4f,5f)
        .column(6f,7f,8f,9f,10f)
        .build();
         ColumnVector expected = ColumnVector.fromFloats(1f,6f,2f,7f,3f,8f,4f,9f,5f,10f);
         ColumnVector actual = t.interleaveColumns()) {
      assertColumnsAreEqual(expected, actual);
    }
  }

  @Test
  void testInterleaveDecimalColumns() {
    try (Table t = new Table.TestBuilder()
        .decimal32Column(-2, 123, 456, 789)
        .decimal32Column(-2,-100, -200, -300)
        .build();
         ColumnVector expected = ColumnVector.decimalFromInts(-2, 123, -100, 456, -200, 789, -300);
         ColumnVector actual = t.interleaveColumns()) {
      assertColumnsAreEqual(expected, actual);
    }
    try (Table t = new Table.TestBuilder()
        .decimal64Column(-5, 123456790L, 987654321L)
        .decimal64Column(-5,-123456790L, -987654321L)
        .build();
         ColumnVector expected = ColumnVector.decimalFromLongs(-5, 123456790L, -123456790L, 987654321L, -987654321L);
         ColumnVector actual = t.interleaveColumns()) {
      assertColumnsAreEqual(expected, actual);
    }
  }

  @Test
  void testInterleaveStringColumns() {
    try (Table t = new Table.TestBuilder()
        .column("a", "b", "c")
        .column("d", "e", "f")
        .build();
         ColumnVector expected = ColumnVector.fromStrings("a", "d", "b", "e", "c", "f");
         ColumnVector actual = t.interleaveColumns()) {
      assertColumnsAreEqual(expected, actual);
    }
  }

  @Test
  void testInterleaveMixedColumns() {
    try (Table t = new Table.TestBuilder()
        .column(1f,2f,3f,4f,5f)
        .column(6,7,8,9,10)
        .build()) {
      assertThrows(CudfException.class, () -> t.interleaveColumns(),
          "All columns must have the same data type in interleave_columns");
    }
  }

  @Test
  void testConcatNoNulls() {
    try (Table t1 = new Table.TestBuilder()
        .column(1, 2, 3)
        .column("1", "2", "3")
        .timestampMicrosecondsColumn(1L, 2L, 3L)
        .column(11.0, 12.0, 13.0)
        .decimal32Column(-3, 1, 2, 3)
        .decimal64Column(-10, 1L, 2L, 3L)
        .build();
         Table t2 = new Table.TestBuilder()
             .column(4, 5)
             .column("4", "3")
             .timestampMicrosecondsColumn(4L, 3L)
             .column(14.0, 15.0)
             .decimal32Column(-3, 4, 5)
             .decimal64Column(-10, 4L, 5L)
             .build();
         Table t3 = new Table.TestBuilder()
             .column(6, 7, 8, 9)
             .column("4", "1", "2", "2")
             .timestampMicrosecondsColumn(4L, 1L, 2L, 2L)
             .column(16.0, 17.0, 18.0, 19.0)
             .decimal32Column(-3, 6, 7, 8, 9)
             .decimal64Column(-10, 6L, 7L, 8L, 9L)
             .build();
         Table concat = Table.concatenate(t1, t2, t3);
         Table expected = new Table.TestBuilder()
             .column(1, 2, 3, 4, 5, 6, 7, 8, 9)
             .column("1", "2", "3", "4", "3", "4", "1", "2", "2")
             .timestampMicrosecondsColumn(1L, 2L, 3L, 4L, 3L, 4L, 1L, 2L, 2L)
             .column(11.0, 12.0, 13.0, 14.0, 15.0, 16.0, 17.0, 18.0, 19.0)
             .decimal32Column(-3, 1, 2, 3, 4, 5, 6, 7, 8, 9)
             .decimal64Column(-10, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L)
             .build()) {
      assertTablesAreEqual(expected, concat);
    }
  }

  @Test
  void testConcatWithNulls() {
    try (Table t1 = new Table.TestBuilder()
        .column(1, null, 3)
        .column(11.0, 12.0, 13.0)
        .decimal32Column(-3, 1, null, 3)
        .decimal64Column(-10, 11L, 12L, 13L)
        .build();
         Table t2 = new Table.TestBuilder()
             .column(4, null)
             .column(14.0, 15.0)
             .decimal32Column(-3, 4, null)
             .decimal64Column(-10, 14L, 15L)
             .build();
         Table t3 = new Table.TestBuilder()
             .column(6, 7, 8, 9)
             .column(null, null, 18.0, 19.0)
             .decimal32Column(-3, 6, 7, 8, 9)
             .decimal64Column(-10, null, null, 18L, 19L)
             .build();
         Table concat = Table.concatenate(t1, t2, t3);
         Table expected = new Table.TestBuilder()
             .column(1, null, 3, 4, null, 6, 7, 8, 9)
             .column(11.0, 12.0, 13.0, 14.0, 15.0, null, null, 18.0, 19.0)
             .decimal32Column(-3, 1, null, 3, 4, null, 6, 7, 8, 9)
             .decimal64Column(-10, 11L, 12L, 13L, 14L, 15L, null, null, 18L, 19L)
             .build()) {
      assertTablesAreEqual(expected, concat);
    }
  }

  @Test
  void testContiguousSplit() {
    ContiguousTable[] splits = null;
    try (Table t1 = new Table.TestBuilder()
        .column(10, 12, 14, 16, 18, 20, 22, 24, null, 28)
        .column(50, 52, 54, 56, 58, 60, 62, 64, 66, null)
        .decimal32Column(-3, 10, 12, 14, 16, 18, 20, 22, 24, null, 28)
        .decimal64Column(-8, 50L, 52L, 54L, 56L, 58L, 60L, 62L, 64L, 66L, null)
        .build()) {
      splits = t1.contiguousSplit(2, 5, 9);
      assertEquals(4, splits.length);
      assertEquals(2, splits[0].getRowCount());
      assertEquals(2, splits[0].getTable().getRowCount());
      assertEquals(3, splits[1].getRowCount());
      assertEquals(3, splits[1].getTable().getRowCount());
      assertEquals(4, splits[2].getRowCount());
      assertEquals(4, splits[2].getTable().getRowCount());
      assertEquals(1, splits[3].getRowCount());
      assertEquals(1, splits[3].getTable().getRowCount());
    } finally {
      if (splits != null) {
        for (int i = 0; i < splits.length; i++) {
          splits[i].close();
        }
      }
    }
  }

  @Test
  void testContiguousSplitWithStrings() {
    ContiguousTable[] splits = null;
    try (Table t1 = new Table.TestBuilder()
        .column(10, 12, 14, 16, 18, 20, 22, 24, null, 28)
        .column(50, 52, 54, 56, 58, 60, 62, 64, 66, null)
        .column("A", "B", "C", "D", "E", "F", "G", "H", "I", "J")
        .decimal32Column(-3, 10, 12, 14, 16, 18, 20, 22, 24, null, 28)
        .decimal64Column(-8, 50L, 52L, 54L, 56L, 58L, 60L, 62L, 64L, 66L, null)
        .build()) {
      splits = t1.contiguousSplit(2, 5, 9);
      assertEquals(4, splits.length);
      assertEquals(2, splits[0].getRowCount());
      assertEquals(2, splits[0].getTable().getRowCount());
      assertEquals(3, splits[1].getRowCount());
      assertEquals(3, splits[1].getTable().getRowCount());
      assertEquals(4, splits[2].getRowCount());
      assertEquals(4, splits[2].getTable().getRowCount());
      assertEquals(1, splits[3].getRowCount());
      assertEquals(1, splits[3].getTable().getRowCount());
    } finally {
      if (splits != null) {
        for (int i = 0; i < splits.length; i++) {
          splits[i].close();
        }
      }
    }
  }

  @Test
  void testPartStability() {
    final int PARTS = 5;
    int expectedPart = -1;
    try (Table start = new Table.TestBuilder().column(0).build();
         PartitionedTable out = start.onColumns(0).hashPartition(PARTS)) {
      // Lets figure out what partitions this is a part of.
      int[] parts = out.getPartitions();
      for (int i = 0; i < parts.length; i++) {
        if (parts[i] > 0) {
          expectedPart = i;
        }
      }
    }
    final int COUNT = 20;
    for (int numEntries = 1; numEntries < COUNT; numEntries++) {
      try (ColumnVector data = ColumnVector.build(DType.INT32, numEntries, Range.appendInts(0, numEntries));
           Table t = new Table(data);
           PartitionedTable out = t.onColumns(0).hashPartition(PARTS);
           HostColumnVector tmp = out.getColumn(0).copyToHost()) {
        // Now we need to get the range out for the partition we expect
        int[] parts = out.getPartitions();
        int start = expectedPart == 0 ? 0 : parts[expectedPart - 1];
        int end = parts[expectedPart];
        boolean found = false;
        for (int i = start; i < end; i++) {
          if (tmp.getInt(i) == 0) {
            found = true;
            break;
          }
        }
        assertTrue(found);
      }
    }
  }

  @Test
  void testPartition() {
    try (Table t = new Table.TestBuilder()
        .column(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        .build();
         ColumnVector parts = ColumnVector
             .fromInts(1, 2, 1, 2, 1, 2, 1, 2, 1, 2);
         PartitionedTable pt = t.partition(parts, 3);
         Table expected = new Table.TestBuilder()
             .column(1, 3, 5, 7, 9, 2, 4, 6, 8, 10)
             .build()) {
      int[] partCutoffs = pt.getPartitions();
      assertArrayEquals(new int[]{0, 0, 5}, partCutoffs);
      assertTablesAreEqual(expected, pt.getTable());
    }
  }

  @Test
  void testIdentityHashPartition() {
    final int count = 1024 * 1024;
    try (ColumnVector aIn = ColumnVector.build(DType.INT64, count, Range.appendLongs(count));
         ColumnVector bIn = ColumnVector.build(DType.INT32, count, (b) -> {
           for (int i = 0; i < count; i++) {
             b.append(i / 2);
           }
         });
         ColumnVector cIn = ColumnVector.build(DType.STRING, count, (b) -> {
           for (int i = 0; i < count; i++) {
             b.appendUTF8String(String.valueOf(i).getBytes());
           }
         })) {

      HashSet<Long> expected = new HashSet<>();
      for (long i = 0; i < count; i++) {
        expected.add(i);
      }
      try (Table input = new Table(new ColumnVector[]{aIn, bIn, cIn});
           PartitionedTable output = input.onColumns(0).hashPartition(HashType.IDENTITY, 5)) {
        int[] parts = output.getPartitions();
        assertEquals(5, parts.length);
        assertEquals(0, parts[0]);
        int previous = 0;
        long rows = 0;
        for (int i = 1; i < parts.length; i++) {
          assertTrue(parts[i] >= previous);
          rows += parts[i] - previous;
          previous = parts[i];
        }
        assertTrue(rows <= count);
        try (HostColumnVector aOut = output.getColumn(0).copyToHost();
             HostColumnVector bOut = output.getColumn(1).copyToHost();
             HostColumnVector cOut = output.getColumn(2).copyToHost()) {

          for (int i = 0; i < count; i++) {
            long fromA = aOut.getLong(i);
            long fromB = bOut.getInt(i);
            String fromC = cOut.getJavaString(i);
            assertTrue(expected.remove(fromA));
            assertEquals(fromA / 2, fromB);
            assertEquals(String.valueOf(fromA), fromC, "At Index " + i);
          }
          assertTrue(expected.isEmpty());
        }
      }
    }
  }

  @Test
  void testHashPartition() {
    final int count = 1024 * 1024;
    try (ColumnVector aIn = ColumnVector.build(DType.INT64, count, Range.appendLongs(count));
         ColumnVector bIn = ColumnVector.build(DType.INT32, count, (b) -> {
           for (int i = 0; i < count; i++) {
             b.append(i / 2);
           }
         });
         ColumnVector cIn = ColumnVector.build(DType.STRING, count, (b) -> {
           for (int i = 0; i < count; i++) {
             b.appendUTF8String(String.valueOf(i).getBytes());
           }
         })) {

      HashSet<Long> expected = new HashSet<>();
      for (long i = 0; i < count; i++) {
        expected.add(i);
      }
      try (Table input = new Table(new ColumnVector[]{aIn, bIn, cIn});
           PartitionedTable output = input.onColumns(0).hashPartition(5)) {
        int[] parts = output.getPartitions();
        assertEquals(5, parts.length);
        assertEquals(0, parts[0]);
        int previous = 0;
        long rows = 0;
        for (int i = 1; i < parts.length; i++) {
          assertTrue(parts[i] >= previous);
          rows += parts[i] - previous;
          previous = parts[i];
        }
        assertTrue(rows <= count);
        try (HostColumnVector aOut = output.getColumn(0).copyToHost();
             HostColumnVector bOut = output.getColumn(1).copyToHost();
             HostColumnVector cOut = output.getColumn(2).copyToHost()) {

          for (int i = 0; i < count; i++) {
            long fromA = aOut.getLong(i);
            long fromB = bOut.getInt(i);
            String fromC = cOut.getJavaString(i);
            assertTrue(expected.remove(fromA));
            assertEquals(fromA / 2, fromB);
            assertEquals(String.valueOf(fromA), fromC, "At Index " + i);
          }
          assertTrue(expected.isEmpty());
        }
      }
    }
  }

  @Test
  void testSerializationRoundTripEmpty() throws IOException {
    DataType listStringsType = new ListType(true, new BasicType(true, DType.STRING));
    DataType mapType = new ListType(true,
        new StructType(true,
            new BasicType(false, DType.STRING),
            new BasicType(false, DType.STRING)));
    DataType structType = new StructType(true,
        new BasicType(true, DType.INT8),
        new BasicType(false, DType.FLOAT32));
    try (ColumnVector emptyInt = ColumnVector.fromInts();
         ColumnVector emptyDouble = ColumnVector.fromDoubles();
         ColumnVector emptyString = ColumnVector.fromStrings();
         ColumnVector emptyListString = ColumnVector.fromLists(listStringsType);
         ColumnVector emptyMap = ColumnVector.fromLists(mapType);
         ColumnVector emptyStruct = ColumnVector.fromStructs(structType);
         Table t = new Table(emptyInt, emptyInt, emptyDouble, emptyString,
             emptyListString, emptyMap, emptyStruct)) {
      ByteArrayOutputStream bout = new ByteArrayOutputStream();
      JCudfSerialization.writeToStream(t, bout, 0, 0);
      ByteArrayInputStream bin = new ByteArrayInputStream(bout.toByteArray());
      DataInputStream din = new DataInputStream(bin);
      try (JCudfSerialization.TableAndRowCountPair result = JCudfSerialization.readTableFrom(din)) {
        assertTablesAreEqual(t, result.getTable());
        assertEquals(result.getTable(), result.getContiguousTable().getTable());
        assertNotNull(result.getContiguousTable().getBuffer());
      }
    }
  }

  @Test
  void testSerializationZeroColumns() throws IOException {
    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    JCudfSerialization.writeRowsToStream(bout, 10);
    ByteArrayInputStream bin = new ByteArrayInputStream(bout.toByteArray());
    try (JCudfSerialization.TableAndRowCountPair result = JCudfSerialization.readTableFrom(bin)) {
      assertNull(result.getTable());
      assertNull(result.getContiguousTable());
      assertEquals(10, result.getNumRows());
    }
  }

  @Test
  void testSerializationZeroColsZeroRows() throws IOException {
    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    JCudfSerialization.writeRowsToStream(bout, 0);
    ByteArrayInputStream bin = new ByteArrayInputStream(bout.toByteArray());
    try (JCudfSerialization.TableAndRowCountPair result = JCudfSerialization.readTableFrom(bin)) {
      assertNull(result.getTable());
      assertNull(result.getContiguousTable());
      assertEquals(0, result.getNumRows());
    }
  }

  @Test
  void testSerializationRoundTripConcatOnHostEmpty() throws IOException {
    DataType listStringsType = new ListType(true, new BasicType(true, DType.STRING));
    DataType mapType = new ListType(true,
        new StructType(true,
            new BasicType(false, DType.STRING),
            new BasicType(false, DType.STRING)));
    DataType structType = new StructType(true,
        new BasicType(true, DType.INT8),
        new BasicType(false, DType.FLOAT32));
    try (ColumnVector emptyInt = ColumnVector.fromInts();
         ColumnVector emptyDouble = ColumnVector.fromDoubles();
         ColumnVector emptyString = ColumnVector.fromStrings();
         ColumnVector emptyListString = ColumnVector.fromLists(listStringsType);
         ColumnVector emptyMap = ColumnVector.fromLists(mapType);
         ColumnVector emptyStruct = ColumnVector.fromStructs(structType);
         Table t = new Table(emptyInt, emptyInt, emptyDouble, emptyString,
             emptyListString, emptyMap, emptyStruct)) {
      ByteArrayOutputStream bout = new ByteArrayOutputStream();
      JCudfSerialization.writeToStream(t, bout, 0, 0);
      JCudfSerialization.writeToStream(t, bout, 0, 0);
      ByteArrayInputStream bin = new ByteArrayInputStream(bout.toByteArray());
      DataInputStream din = new DataInputStream(bin);

      ArrayList<JCudfSerialization.SerializedTableHeader> headers = new ArrayList<>();
      List<HostMemoryBuffer> buffers = new ArrayList<>();
      try {
        JCudfSerialization.SerializedTableHeader head;
        long numRows = 0;
        do {
          head = new JCudfSerialization.SerializedTableHeader(din);
          if (head.wasInitialized()) {
            HostMemoryBuffer buff = HostMemoryBuffer.allocate(head.getDataLen());
            buffers.add(buff);
            JCudfSerialization.readTableIntoBuffer(din, head, buff);
            assert head.wasDataRead();
            numRows += head.getNumRows();
            assert numRows <= Integer.MAX_VALUE;
            headers.add(head);
          }
        } while (head.wasInitialized());
        assert numRows == t.getRowCount();
        try (Table found = JCudfSerialization.readAndConcat(
            headers.toArray(new JCudfSerialization.SerializedTableHeader[headers.size()]),
            buffers.toArray(new HostMemoryBuffer[buffers.size()]))) {
          assertTablesAreEqual(t, found);
        }
      } finally {
        for (HostMemoryBuffer buff: buffers) {
          buff.close();
        }
      }
    }
  }

  @Test
  void testRoundRobinPartition() {
    try (Table t = new Table.TestBuilder()
        .column(     100,      202,      3003,    40004,        5,      -60,       1,      null,        3,  null,        5,     null,        7, null,        9,      null,       11,      null,        13,      null,       15)
        .column(    true,     true,     false,    false,     true,     null,     true,     true,     null, false,    false,     null,     true, true,     null,     false,    false,      null,      true,      true,     null)
        .column( (byte)1,  (byte)2,      null,  (byte)4,  (byte)5,  (byte)6,  (byte)1,  (byte)2,  (byte)3,  null,  (byte)5,  (byte)6,  (byte)7, null,  (byte)9,  (byte)10, (byte)11,      null,  (byte)13,  (byte)14, (byte)15)
        .column((short)6, (short)5,  (short)4,     null, (short)2, (short)1, (short)1, (short)2, (short)3,  null, (short)5, (short)6, (short)7, null, (short)9, (short)10,     null, (short)12, (short)13, (short)14,     null)
        .column(      1L,     null,     1001L,      50L,   -2000L,     null,       1L,       2L,       3L,    4L,     null,       6L,       7L,   8L,       9L,      null,      11L,       12L,       13L,       14L,     null)
        .column(   10.1f,      20f, Float.NaN,  3.1415f,     -60f,     null,       1f,       2f,       3f,    4f,       5f,     null,       7f,   8f,       9f,       10f,      11f,      null,       13f,       14f,      15f)
        .column(    10.1,     20.0,      33.1,   3.1415,    -60.5,     null,       1.,       2.,       3.,    4.,       5.,       6.,     null,   8.,       9.,       10.,      11.,       12.,      null,       14.,      15.)
        .timestampDayColumn(99, 100,      101,      102,      103,      104,        1,        2,        3,     4,        5,        6,        7, null,        9,        10,       11,        12,        13,      null,       15)
        .timestampMillisecondsColumn(9L, 1006L, 101L, 5092L, null,      88L,       1L,       2L,       3L,    4L,       5L,       6L,       7L,   8L,     null,       10L,      11L,       12L,       13L,       14L,      15L)
        .timestampSecondsColumn(1L, null,  3L,       4L,       5L,       6L,       1L,       2L,       3L,    4L,       5L,       6L,       7L,   8L,       9L,      null,      11L,       12L,       13L,       14L,      15L)
        .column(     "A",      "B",       "C",      "D",     null, "TESTING",     "1",      "2",      "3",   "4",      "5",      "6",      "7", null,      "9",      "10",     "11",      "12",      "13",      null,     "15")
        .column(     "A",      "A",       "C",      "C",     null, "TESTING",     "1",      "2",      "3",   "4",      "5",      "6",      "7", null,      "9",      "10",     "11",      "12",      "13",      null,     "15")
        .decimal32Column(-3, 100,      202,      3003,    40004,        5,      -60,       1,      null,        3,  null,        5,     null,        7, null,        9,      null,       11,      null,        13,      null,       15)
        .decimal64Column(      -8, 1L,     null,     1001L,      50L,   -2000L,     null,       1L,       2L,       3L,    4L,     null,       6L,       7L,   8L,       9L,      null,      11L,       12L,       13L,       14L,     null)
        .build()) {
      try (Table expectedTable = new Table.TestBuilder()
          .column(     100,   40004,        1,  null,        7,      null,        13,      202,        5,     null,        5, null,       11,      null,      3003,       -60,        3,     null,        9,      null,       15)
          .column(    true,   false,     true, false,     true,     false,      true,     true,     true,     true,    false, true,    false,      true,     false,      null,     null,     null,     null,      null,     null)
          .column( (byte)1, (byte)4,  (byte)1,  null,  (byte)7,  (byte)10,  (byte)13,  (byte)2,  (byte)5,  (byte)2,  (byte)5, null, (byte)11,  (byte)14,      null,   (byte)6,  (byte)3,  (byte)6,  (byte)9,      null, (byte)15)
          .column((short)6,    null, (short)1,  null, (short)7, (short)10, (short)13, (short)5, (short)2, (short)2, (short)5, null,     null, (short)14,  (short)4,  (short)1, (short)3, (short)6, (short)9, (short)12,     null)
          .column(      1L,     50L,       1L,    4L,       7L,      null,       13L,     null,   -2000L,       2L,     null,   8L,      11L,       14L,     1001L,      null,       3L,       6L,       9L,       12L,     null)
          .column(   10.1f, 3.1415f,       1f,    4f,       7f,       10f,       13f,      20f,     -60f,       2f,       5f,   8f,      11f,       14f, Float.NaN,      null,       3f,     null,       9f,      null,      15f)
          .column(    10.1,  3.1415,       1.,    4.,     null,       10.,      null,     20.0,    -60.5,       2.,       5.,   8.,      11.,       14.,      33.1,      null,       3.,       6.,       9.,       12.,      15.)
          .timestampDayColumn(99, 102,      1,     4,        7,        10,        13,      100,      103,        2,        5, null,       11,      null,       101,       104,        3,        6,        9,        12,       15)
          .timestampMillisecondsColumn(9L, 5092L, 1L, 4L,   7L,       10L,       13L,    1006L,     null,       2L,       5L,   8L,      11L,       14L,      101L,       88L,       3L,       6L,     null,       12L,      15L)
          .timestampSecondsColumn(1L, 4L,   1L,   4L,       7L,      null,       13L,     null,       5L,       2L,       5L,   8L,      11L,       14L,        3L,        6L,       3L,       6L,       9L,       12L,      15L)
          .column(     "A",     "D",       "1",  "4",      "7",      "10",      "13",      "B",     null,      "2",      "5", null,     "11",      null,       "C", "TESTING",      "3",      "6",      "9",      "12",     "15")
          .column(     "A",     "C",       "1",  "4",      "7",      "10",      "13",      "A",     null,      "2",      "5", null,     "11",      null,       "C", "TESTING",      "3",      "6",      "9",      "12",     "15")
          .decimal32Column(-3,     100,   40004,        1,  null,        7,      null,        13,      202,        5,     null,        5, null,       11,      null,      3003,       -60,        3,     null,        9,      null,       15)
          .decimal64Column(-8, 1L,     50L,       1L,    4L,       7L,      null,       13L,     null,   -2000L,       2L,     null,   8L,      11L,       14L,     1001L,      null,       3L,       6L,       9L,       12L,     null)
          .build();
           PartitionedTable pt = t.roundRobinPartition(3, 0)) {
        assertTablesAreEqual(expectedTable, pt.getTable());
        int[] parts = pt.getPartitions();
        assertEquals(3, parts.length);
        assertEquals(0, parts[0]);
        assertEquals(7, parts[1]);
        assertEquals(14, parts[2]);
      }

      try (Table expectedTable = new Table.TestBuilder()
          .column(      3003,       -60,        3,     null,        9,      null,       15,     100,   40004,        1,  null,        7,      null,        13,      202,        5,     null,        5, null,       11,      null)
          .column(     false,      null,     null,     null,     null,      null,     null,    true,   false,     true, false,     true,     false,      true,     true,     true,     true,    false, true,    false,      true)
          .column(      null,   (byte)6,  (byte)3,  (byte)6,  (byte)9,      null, (byte)15, (byte)1, (byte)4,  (byte)1,  null,  (byte)7,  (byte)10,  (byte)13,  (byte)2,  (byte)5,  (byte)2,  (byte)5, null, (byte)11,  (byte)14)
          .column(  (short)4,  (short)1, (short)3, (short)6, (short)9, (short)12,     null,(short)6,    null, (short)1,  null, (short)7, (short)10, (short)13, (short)5, (short)2, (short)2, (short)5, null,     null, (short)14)
          .column(     1001L,      null,       3L,       6L,       9L,       12L,     null,      1L,     50L,       1L,    4L,       7L,      null,       13L,     null,   -2000L,       2L,     null,   8L,      11L,       14L)
          .column( Float.NaN,      null,       3f,     null,       9f,      null,      15f,   10.1f, 3.1415f,       1f,    4f,       7f,       10f,       13f,      20f,     -60f,       2f,       5f,   8f,      11f,       14f)
          .column(      33.1,      null,       3.,       6.,       9.,       12.,      15.,    10.1,  3.1415,       1.,    4.,     null,       10.,      null,     20.0,    -60.5,       2.,       5.,   8.,      11.,       14.)
          .timestampDayColumn(101, 104,         3,        6,        9,        12,       15,      99,     102,        1,     4,        7,        10,        13,      100,      103,        2,        5, null,       11,      null)
          .timestampMillisecondsColumn(101L, 88L, 3L,    6L,     null,       12L,      15L,      9L,   5092L,       1L,    4L,       7L,       10L,       13L,    1006L,     null,       2L,       5L,   8L,      11L,       14L)
          .timestampSecondsColumn(3L, 6L,      3L,       6L,       9L,       12L,      15L,      1L,      4L,       1L,    4L,       7L,      null,       13L,     null,       5L,       2L,       5L,   8L,      11L,       14L)
          .column(       "C", "TESTING",      "3",      "6",      "9",      "12",     "15",     "A",     "D",       "1",  "4",      "7",      "10",      "13",      "B",     null,      "2",      "5", null,     "11",      null)
          .column(       "C", "TESTING",      "3",      "6",      "9",      "12",     "15",     "A",     "C",       "1",  "4",      "7",      "10",      "13",      "A",     null,      "2",      "5", null,     "11",      null)
          .decimal32Column(-3,      3003,       -60,        3,     null,        9,      null,       15,     100,   40004,        1,  null,        7,      null,        13,      202,        5,     null,        5, null,       11,      null)
          .decimal64Column(-8,     1001L,      null,       3L,       6L,       9L,       12L,     null,      1L,     50L,       1L,    4L,       7L,      null,       13L,     null,   -2000L,       2L,     null,   8L,      11L,       14L)
          .build();
           PartitionedTable pt = t.roundRobinPartition(3, 1)) {
        assertTablesAreEqual(expectedTable, pt.getTable());
        int[] parts = pt.getPartitions();
        assertEquals(3, parts.length);
        assertEquals(0, parts[0]);
        assertEquals(7, parts[1]);
        assertEquals(14, parts[2]);
      }

      try (Table expectedTable = new Table.TestBuilder()
          .column(      202,        5,     null,        5, null,       11,      null,      3003,       -60,        3,     null,        9,      null,       15,     100,   40004,        1,  null,        7,      null,        13)
          .column(     true,     true,     true,    false, true,    false,      true,     false,      null,     null,     null,     null,      null,     null,    true,   false,     true, false,     true,     false,      true)
          .column(  (byte)2,  (byte)5,  (byte)2,  (byte)5, null, (byte)11,  (byte)14,      null,   (byte)6,  (byte)3,  (byte)6,  (byte)9,      null, (byte)15, (byte)1, (byte)4,  (byte)1,  null,  (byte)7,  (byte)10,  (byte)13)
          .column( (short)5, (short)2, (short)2, (short)5, null,     null, (short)14,  (short)4,  (short)1, (short)3, (short)6, (short)9, (short)12,     null,(short)6,    null, (short)1,  null, (short)7, (short)10, (short)13)
          .column(     null,   -2000L,       2L,     null,   8L,      11L,       14L,     1001L,      null,       3L,       6L,       9L,       12L,     null,      1L,     50L,       1L,    4L,       7L,      null,       13L)
          .column(      20f,     -60f,       2f,       5f,   8f,      11f,       14f, Float.NaN,      null,       3f,     null,       9f,      null,      15f,   10.1f, 3.1415f,       1f,    4f,       7f,       10f,       13f)
          .column(     20.0,    -60.5,       2.,       5.,   8.,      11.,       14.,      33.1,      null,       3.,       6.,       9.,       12.,      15.,    10.1,  3.1415,       1.,    4.,     null,       10.,      null)
          .timestampDayColumn(100, 103,       2,        5, null,       11,      null,       101,       104,        3,        6,        9,        12,       15,      99,     102,        1,     4,        7,        10,        13)
          .timestampMillisecondsColumn(1006L, null, 2L, 5L,  8L,      11L,       14L,      101L,      88L,       3L,       6L,      null,       12L,      15L,      9L,   5092L,       1L,    4L,       7L,       10L,       13L)
          .timestampSecondsColumn(null, 5L,  2L,       5L,   8L,      11L,       14L,        3L,        6L,       3L,       6L,       9L,       12L,      15L,      1L,      4L,       1L,    4L,       7L,      null,       13L)
          .column(      "B",     null,      "2",      "5", null,     "11",      null,       "C", "TESTING",      "3",      "6",      "9",      "12",     "15",     "A",     "D",       "1",  "4",      "7",      "10",      "13")
          .column(      "A",     null,      "2",      "5", null,     "11",      null,       "C", "TESTING",      "3",      "6",      "9",      "12",     "15",     "A",     "C",       "1",  "4",      "7",      "10",      "13")
          .decimal32Column(-3,      202,        5,     null,        5, null,       11,      null,      3003,       -60,        3,     null,        9,      null,       15,     100,   40004,        1,  null,        7,      null,        13)
          .decimal64Column(-8,     null,   -2000L,       2L,     null,   8L,      11L,       14L,     1001L,      null,       3L,       6L,       9L,       12L,     null,      1L,     50L,       1L,    4L,       7L,      null,       13L)
          .build();
           PartitionedTable pt = t.roundRobinPartition(3, 2)) {
        assertTablesAreEqual(expectedTable, pt.getTable());
        int[] parts = pt.getPartitions();
        assertEquals(3, parts.length);
        assertEquals(0, parts[0]);
        assertEquals(7, parts[1]);
        assertEquals(14, parts[2]);
      }
    }
  }

  @Test
  void testSerializationRoundTripConcatHostSide() throws IOException {
    try (Table t = buildTestTable()) {
      for (int sliceAmount = 1; sliceAmount < t.getRowCount(); sliceAmount ++) {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        for (int i = 0; i < t.getRowCount(); i += sliceAmount) {
          int len = (int) Math.min(t.getRowCount() - i, sliceAmount);
          JCudfSerialization.writeToStream(t, bout, i, len);
        }
        ByteArrayInputStream bin = new ByteArrayInputStream(bout.toByteArray());
        DataInputStream din = new DataInputStream(bin);
        ArrayList<JCudfSerialization.SerializedTableHeader> headers = new ArrayList<>();
        List<HostMemoryBuffer> buffers = new ArrayList<>();
        try {
          JCudfSerialization.SerializedTableHeader head;
          long numRows = 0;
          do {
            head = new JCudfSerialization.SerializedTableHeader(din);
            if (head.wasInitialized()) {
              HostMemoryBuffer buff = HostMemoryBuffer.allocate(100 * 1024);
              buffers.add(buff);
              JCudfSerialization.readTableIntoBuffer(din, head, buff);
              assert head.wasDataRead();
              numRows += head.getNumRows();
              assert numRows <= Integer.MAX_VALUE;
              headers.add(head);
            }
          } while (head.wasInitialized());
          assert numRows == t.getRowCount();
          try (Table found = JCudfSerialization.readAndConcat(
              headers.toArray(new JCudfSerialization.SerializedTableHeader[headers.size()]),
              buffers.toArray(new HostMemoryBuffer[buffers.size()]))) {
            assertPartialTablesAreEqual(t, 0, t.getRowCount(), found, false, false);
          }
        } finally {
          for (HostMemoryBuffer buff: buffers) {
            buff.close();
          }
        }
      }
    }
  }

  @Test
  void testConcatHost() throws IOException {
    try (Table t1 = new Table.TestBuilder()
        .column(
            1, 2, null, 4, 5, 6, 7, 8, 9, 10, null, 12, 13, 14, null, null,
            1, 2, null, 4 , 5, 6, 7, 8, 9, 10, null, 12, 13, 14, null, null)
        .decimal32Column(-3,
            1, 2, null, 4, 5, 6, 7, 8, 9, 10, null, 12, 13, 14, null, null,
            1, 2, null, 4 , 5, 6, 7, 8, 9, 10, null, 12, 13, 14, null, null)
        .build();
         Table expected = new Table.TestBuilder()
             .column(
                 null, 12, 13, 14, null, null,
                 1, 2, null, 4 , 5, 6, 7, 8, 9, 10, null, 12, 13, 14, null, null,
                 1, 2, null, 4 , 5, 6, 7, 8, 9, 10, null, 12, 13, 14, null, null,
                 1, 2, null, 4 , 5, 6, 7, 8, 9, 10, null, 12, 13, 14, null, null,
                 null, 12, 13, 14, null, null,
                 1, 2, null, 4 , 5, 6, 7, 8, 9, 10, null, 12, 13, 14, null, null,
                 1, 2, null, 4 , 5, 6, 7, 8, 9, 10, null, 12, 13, 14, null, null,
                 1, 2, null, 4 , 5, 6, 7, 8, 9, 10, null, 12, 13, 14, null, null)
             .decimal32Column(-3,
                 null, 12, 13, 14, null, null,
                 1, 2, null, 4 , 5, 6, 7, 8, 9, 10, null, 12, 13, 14, null, null,
                 1, 2, null, 4 , 5, 6, 7, 8, 9, 10, null, 12, 13, 14, null, null,
                 1, 2, null, 4 , 5, 6, 7, 8, 9, 10, null, 12, 13, 14, null, null,
                 null, 12, 13, 14, null, null,
                 1, 2, null, 4 , 5, 6, 7, 8, 9, 10, null, 12, 13, 14, null, null,
                 1, 2, null, 4 , 5, 6, 7, 8, 9, 10, null, 12, 13, 14, null, null,
                 1, 2, null, 4 , 5, 6, 7, 8, 9, 10, null, 12, 13, 14, null, null)
             .build();
         Table t2 = t1.concatenate(t1, t1)) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      JCudfSerialization.writeToStream(t2, out, 10, t2.getRowCount() - 10);
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(out.toByteArray()));
      JCudfSerialization.SerializedTableHeader header = new JCudfSerialization.SerializedTableHeader(in);
      assert header.wasInitialized();
      try (HostMemoryBuffer buff = HostMemoryBuffer.allocate(header.getDataLen())) {
        JCudfSerialization.readTableIntoBuffer(in, header, buff);
        assert header.wasDataRead();
        try (Table result = JCudfSerialization.readAndConcat(
            new JCudfSerialization.SerializedTableHeader[] {header, header},
            new HostMemoryBuffer[] {buff, buff})) {
          assertPartialTablesAreEqual(expected, 0, expected.getRowCount(), result, false, false);
        }
      }
    }
  }

  @Test
  void testSerializationRoundTripSlicedHostSide() throws IOException {
    try (Table t = buildTestTable()) {
      for (int sliceAmount = 1; sliceAmount < t.getRowCount(); sliceAmount ++) {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        for (int i = 0; i < t.getRowCount(); i += sliceAmount) {
          int len = (int) Math.min(t.getRowCount() - i, sliceAmount);
          JCudfSerialization.writeToStream(t, bout, i, len);
        }
        ByteArrayInputStream bin = new ByteArrayInputStream(bout.toByteArray());
        DataInputStream din = new DataInputStream(bin);
        ArrayList<JCudfSerialization.SerializedTableHeader> headers = new ArrayList<>();
        List<HostMemoryBuffer> buffers = new ArrayList<>();
        try {
          JCudfSerialization.SerializedTableHeader head;
          long numRows = 0;
          do {
            head = new JCudfSerialization.SerializedTableHeader(din);
            if (head.wasInitialized()) {
              HostMemoryBuffer buff = HostMemoryBuffer.allocate(100 * 1024);
              buffers.add(buff);
              JCudfSerialization.readTableIntoBuffer(din, head, buff);
              assert head.wasDataRead();
              numRows += head.getNumRows();
              assert numRows <= Integer.MAX_VALUE;
              headers.add(head);
            }
          } while (head.wasInitialized());
          assert numRows == t.getRowCount();
          ByteArrayOutputStream bout2 = new ByteArrayOutputStream();
          JCudfSerialization.writeConcatedStream(
              headers.toArray(new JCudfSerialization.SerializedTableHeader[headers.size()]),
              buffers.toArray(new HostMemoryBuffer[buffers.size()]), bout2);
          ByteArrayInputStream bin2 = new ByteArrayInputStream(bout2.toByteArray());
          try (JCudfSerialization.TableAndRowCountPair found = JCudfSerialization.readTableFrom(bin2)) {
            assertPartialTablesAreEqual(t, 0, t.getRowCount(), found.getTable(), false, false);
            assertEquals(found.getTable(), found.getContiguousTable().getTable());
            assertNotNull(found.getContiguousTable().getBuffer());
          }
          JCudfSerialization.TableAndRowCountPair tp = JCudfSerialization.readTableFrom(bin2);
          assertNull(tp.getTable());
          assertNull(tp.getContiguousTable());
        } finally {
          for (HostMemoryBuffer buff: buffers) {
            buff.close();
          }
        }
      }
    }
  }

  @Test
  void testSerializationRoundTripSliced() throws IOException {
    try (Table t = buildTestTable()) {
      for (int sliceAmount = 1; sliceAmount < t.getRowCount(); sliceAmount ++) {
        for (int i = 0; i < t.getRowCount(); i += sliceAmount) {
          ByteArrayOutputStream bout = new ByteArrayOutputStream();
          int len = (int) Math.min(t.getRowCount() - i, sliceAmount);
          JCudfSerialization.writeToStream(t, bout, i, len);
          ByteArrayInputStream bin = new ByteArrayInputStream(bout.toByteArray());
          try (JCudfSerialization.TableAndRowCountPair found = JCudfSerialization.readTableFrom(bin)) {
            assertPartialTablesAreEqual(t, i, len, found.getTable(), i == 0 && len == t.getRowCount(), false);
            assertEquals(found.getTable(), found.getContiguousTable().getTable());
            assertNotNull(found.getContiguousTable().getBuffer());
          }
          JCudfSerialization.TableAndRowCountPair tp = JCudfSerialization.readTableFrom(bin);
          assertNull(tp.getTable());
          assertNull(tp.getContiguousTable());
        }
      }
    }
  }

  @Test
  void testSerializationReconstructFromMetadata() throws IOException {
    try (Table t = buildTestTable()) {
      ByteArrayOutputStream bout = new ByteArrayOutputStream();
      JCudfSerialization.writeToStream(t, bout, 0, t.getRowCount());
      ByteArrayInputStream bin = new ByteArrayInputStream(bout.toByteArray());
      try (JCudfSerialization.TableAndRowCountPair trcp = JCudfSerialization.readTableFrom(bin)) {
        ContiguousTable contigTable = trcp.getContiguousTable();
        DeviceMemoryBuffer oldbuf = contigTable.getBuffer();
        try (DeviceMemoryBuffer newbuf = oldbuf.sliceWithCopy(0, oldbuf.getLength())) {
          ByteBuffer metadata = contigTable.getMetadataDirectBuffer();
          try (Table newTable = Table.fromPackedTable(metadata, newbuf)) {
            assertTablesAreEqual(t, newTable);
          }
        }
      }
    }
  }

  @Test
  void testValidityFill() {
    byte[] buff = new byte[2];
    buff[0] = 0;
    int bitsToFill = (buff.length * 8) - 1;
    assertEquals(bitsToFill, JCudfSerialization.fillValidity(buff, 1, bitsToFill));
    assertEquals(buff[0], 0xFFFFFFFE);
    assertEquals(buff[1], 0xFFFFFFFF);
  }

  @Test
  void testGroupByScan() {
    try (Table t1 = new Table.TestBuilder()
        .column( "1",  "1",  "1",  "1",  "1",  "1",  "1",  "2",  "2",  "2",  "2")
        .column(   0,    1,    3,    3,    5,    5,    5,    5,    5,    5,    5)
        .column(12.0, 14.0, 13.0, 17.0, 17.0, 17.0, null, null, 11.0, null, 10.0)
        .column(  -9, null,   -5,   0,     4,    4,    8,    2,    2,    2, null)
        .build()) {
      try (Table result = t1
          .groupBy(GroupByOptions.builder()
              .withKeysSorted(true)
              .withKeysDescending(false, false)
              .build(), 0, 1)
          .scan(GroupByScanAggregation.sum().onColumn(2),
              GroupByScanAggregation.count(NullPolicy.INCLUDE).onColumn(2),
              GroupByScanAggregation.min().onColumn(2),
              GroupByScanAggregation.max().onColumn(2),
              GroupByScanAggregation.rank().onColumn(3),
              GroupByScanAggregation.denseRank().onColumn(3));
           Table expected = new Table.TestBuilder()
               .column( "1",  "1",  "1",  "1",  "1",  "1",  "1",  "2",  "2",  "2",  "2")
               .column(   0,    1,    3,    3,    5,    5,    5,    5,    5,    5,    5)
               .column(12.0, 14.0, 13.0, 30.0, 17.0, 34.0, null, null, 11.0, null, 21.0)
               .column(   0,    0,    0,    1,    0,    1,    2,    0,    1,    2,    3) // odd why is this not 1 based?
               .column(12.0, 14.0, 13.0, 13.0, 17.0, 17.0, null, null, 11.0, null, 10.0)
               .column(12.0, 14.0, 13.0, 17.0, 17.0, 17.0, null, null, 11.0, null, 11.0)
               .column(1, 1, 1, 2, 1, 1, 3, 1, 1, 1, 4)
               .column(1, 1, 1, 2, 1, 1, 2, 1, 1, 1, 2)
               .build()) {
        assertTablesAreEqual(expected, result);
      }
    }
  }

  @Test
  void testGroupByReplaceNulls() {
    try (Table t1 = new Table.TestBuilder()
        .column( "1",  "1",  "1",  "1",  "1",  "1",  "1",  "2",  "2",  "2",  "2")
        .column(   0,    1,    3,    3,    5,    5,    5,    5,    5,    5,    5)
        .column(null, 14.0, 13.0, 17.0, 17.0, 17.0, null, null, 11.0, null, null)
        .build()) {
      try (Table result = t1
          .groupBy(GroupByOptions.builder()
              .withKeysSorted(true)
              .withKeysDescending(false, false)
              .build(), 0, 1)
          .replaceNulls(ReplacePolicy.PRECEDING.onColumn(2),
              ReplacePolicy.FOLLOWING.onColumn(2));
           Table expected = new Table.TestBuilder()
               .column( "1",  "1",  "1",  "1",  "1",  "1",  "1",  "2",  "2",  "2",  "2")
               .column(   0,    1,    3,    3,    5,    5,    5,    5,    5,    5,    5)
               .column(null, 14.0, 13.0, 17.0, 17.0, 17.0, 17.0, null, 11.0, 11.0, 11.0)
               .column(null, 14.0, 13.0, 17.0, 17.0, 17.0, null, 11.0, 11.0, null, null)
               .build()) {
        assertTablesAreEqual(expected, result);
      }
    }
  }

  @Test
  void testGroupByUniqueCount() {
    try (Table t1 = new Table.TestBuilder()
            .column( "1",  "1",  "1",  "1",  "1",  "1")
            .column(   1,    3,    3,    5,    5,    0)
            .column(12.0, 14.0, 13.0, 17.0, 17.0, 17.0)
            .build()) {
      try (Table t3 = t1
              .groupBy(0, 1)
              .aggregate(GroupByAggregation.nunique().onColumn(0));
           Table sorted = t3.orderBy(OrderByArg.asc(0), OrderByArg.asc(1), OrderByArg.asc(2));
           Table expected = new Table.TestBuilder()
                   .column( "1",  "1",  "1",  "1")
                   .column(   0,    1,    3,    5)
                   .column(   1,    1,    1,    1)
                   .build()) {
        assertTablesAreEqual(expected, sorted);
      }
    }
  }

  @Test
  void testGroupByUniqueCountNulls() {
    try (Table t1 = new Table.TestBuilder()
            .column( "1",  "1",  "1",  "1",  "1",  "1")
            .column(   1,    3,    3,    5,    5,    0)
            .column(null, null, 13.0, null, null, null)
            .build()) {
      try (Table t3 = t1
              .groupBy(0, 1)
              .aggregate(GroupByAggregation.nunique(NullPolicy.INCLUDE).onColumn(0));
           Table sorted = t3.orderBy(OrderByArg.asc(0), OrderByArg.asc(1), OrderByArg.asc(2));
           Table expected = new Table.TestBuilder()
                   .column( "1",  "1",  "1",  "1")
                   .column(   0,    1,    3,    5)
                   .column(   1,    1,    1,    1)
                   .build()) {
        assertTablesAreEqual(expected, sorted);
      }
    }
  }

  @Test
  void testGroupByCount() {
    try (Table t1 = new Table.TestBuilder().column( "1",  "1",  "1",  "1",  "1",  "1")
                                           .column(   1,    3,    3,    5,    5,    0)
                                           .column(12.0, 14.0, 13.0, 17.0, 17.0, 17.0)
                                           .build()) {
      try (Table t3 = t1.groupBy(0, 1)
          .aggregate(GroupByAggregation.count().onColumn(0));
           HostColumnVector aggOut1 = t3.getColumn(2).copyToHost()) {
        // verify t3
        assertEquals(4, t3.getRowCount());
        Map<Object, Integer> expectedAggregateResult = new HashMap() {
          {
            // value, count
            put(1, 2);
            put(2, 2);
          }
        };
        for (int i = 0; i < 4; ++i) {
          int key = aggOut1.getInt(i);
          assertTrue(expectedAggregateResult.containsKey(key));
          Integer count = expectedAggregateResult.get(key);
          if (count == 1) {
            expectedAggregateResult.remove(key);
          } else {
            expectedAggregateResult.put(key, count - 1);
          }
        }
      }
    }
  }

  @Test
  void testWindowingCount() {
    try (Table unsorted = new Table.TestBuilder()
        .column(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1) // GBY Key
        .column(1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3) // GBY Key
        .column(1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6) // OBY Key
        .column(7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6) // Agg Column
        .decimal32Column(-1, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3) // Decimal GBY Key
        .decimal64Column(1, 1L, 1L, 2L, 2L, 3L, 3L, 4L, 4L, 5L, 5L, 6L, 6L) // Decimal OBY Key
        .build()) {
      try (Table sorted = unsorted.orderBy(OrderByArg.asc(0), OrderByArg.asc(1), OrderByArg.asc(2));
           Table decSorted = unsorted.orderBy(OrderByArg.asc(0), OrderByArg.asc(4), OrderByArg.asc(5));
           ColumnVector expectSortedAggColumn = ColumnVector.fromBoxedInts(7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6);
           Scalar two = Scalar.fromInt(2);
           Scalar one = Scalar.fromInt(1)) {
        ColumnVector sortedAggColumn = sorted.getColumn(3);
        assertColumnsAreEqual(expectSortedAggColumn, sortedAggColumn);
        ColumnVector decSortedAggColumn = decSorted.getColumn(3);
        assertColumnsAreEqual(expectSortedAggColumn, decSortedAggColumn);

        try (WindowOptions window = WindowOptions.builder()
            .minPeriods(1)
            .window(two, one)
            .build()) {

          try (Table windowAggResults = sorted.groupBy(0, 1)
              .aggregateWindows(RollingAggregation.count().onColumn(3).overWindow(window));
               Table decWindowAggResults = decSorted.groupBy(0, 4)
                   .aggregateWindows(RollingAggregation.count().onColumn(3).overWindow(window));
               ColumnVector expect = ColumnVector.fromBoxedInts(2, 3, 3, 2, 2, 3, 3, 2, 2, 3, 3, 2)) {
            assertColumnsAreEqual(expect, windowAggResults.getColumn(0));
            assertColumnsAreEqual(expect, decWindowAggResults.getColumn(0));
          }
        }
      }
    }
  }

  @Test
  void testWindowingMin() {
    try (Table unsorted = new Table.TestBuilder()
        .column(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1) // GBY Key
        .column(1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3) // GBY Key
        .column(1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6) // OBY Key
        .column(7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6) // Agg Column
        .decimal32Column(-1, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3) // Decimal GBY Key
        .decimal64Column(1, 1L, 1L, 2L, 2L, 3L, 3L, 4L, 4L, 5L, 5L, 6L, 6L) // Decimal OBY Key
        .decimal64Column(2, 7L, 5L, 1L, 9L, 7L, 9L, 8L, 2L, 8L, 0L, 6L, 6L) // Decimal Agg Column
        .build()) {
      try (Table sorted = unsorted.orderBy(OrderByArg.asc(0), OrderByArg.asc(1), OrderByArg.asc(2));
           Table decSorted = unsorted.orderBy(OrderByArg.asc(0), OrderByArg.asc(4), OrderByArg.asc(5));
           ColumnVector expectSortedAggCol = ColumnVector.fromBoxedInts(7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6);
           ColumnVector expectDecSortedAggCol = ColumnVector.decimalFromLongs(2, 7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6);
           Scalar two = Scalar.fromInt(2);
           Scalar one = Scalar.fromInt(1)) {
        ColumnVector sortedAggColumn = sorted.getColumn(3);
        assertColumnsAreEqual(expectSortedAggCol, sortedAggColumn);
        ColumnVector decSortedAggColumn = decSorted.getColumn(6);
        assertColumnsAreEqual(expectDecSortedAggCol, decSortedAggColumn);

        try (WindowOptions window = WindowOptions.builder()
            .minPeriods(1)
            .window(two, one)
            .build()) {

          try (Table windowAggResults = sorted.groupBy(0, 1)
              .aggregateWindows(RollingAggregation.min().onColumn(3).overWindow(window));
               Table decWindowAggResults = decSorted.groupBy(0, 4)
                   .aggregateWindows(RollingAggregation.min().onColumn(6).overWindow(window));
               ColumnVector expect = ColumnVector.fromBoxedInts(5, 1, 1, 1, 7, 7, 2, 2, 0, 0, 0, 6);
               ColumnVector decExpect = ColumnVector.decimalFromLongs(2, 5, 1, 1, 1, 7, 7, 2, 2, 0, 0, 0, 6)) {
            assertColumnsAreEqual(expect, windowAggResults.getColumn(0));
            assertColumnsAreEqual(decExpect, decWindowAggResults.getColumn(0));
          }
        }
      }
    }
  }

  @Test
  void testWindowingMax() {
    try (Table unsorted = new Table.TestBuilder()
        .column(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1) // GBY Key
        .column(1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3) // GBY Key
        .column(1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6) // OBY Key
        .column(7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6) // Agg Column
        .decimal32Column(-1, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3) // Decimal GBY Key
        .decimal64Column(1, 1L, 1L, 2L, 2L, 3L, 3L, 4L, 4L, 5L, 5L, 6L, 6L) // Decimal OBY Key
        .decimal64Column(2, 7L, 5L, 1L, 9L, 7L, 9L, 8L, 2L, 8L, 0L, 6L, 6L) // Decimal Agg Column
        .build()) {
      try (Table sorted = unsorted.orderBy(OrderByArg.asc(0), OrderByArg.asc(1), OrderByArg.asc(2));
           Table decSorted = unsorted.orderBy(OrderByArg.asc(0), OrderByArg.asc(4), OrderByArg.asc(5));
           ColumnVector expectSortedAggCol = ColumnVector.fromBoxedInts(7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6);
           ColumnVector expectDecSortedAggCol = ColumnVector.decimalFromLongs(2, 7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6);
           Scalar two = Scalar.fromInt(2);
           Scalar one = Scalar.fromInt(1)) {
        ColumnVector sortedAggColumn = sorted.getColumn(3);
        assertColumnsAreEqual(expectSortedAggCol, sortedAggColumn);
        ColumnVector decSortedAggColumn = decSorted.getColumn(6);
        assertColumnsAreEqual(expectDecSortedAggCol, decSortedAggColumn);

        try (WindowOptions window = WindowOptions.builder()
            .minPeriods(1)
            .window(two, one)
            .build()) {

          try (Table windowAggResults = sorted.groupBy(0, 1)
              .aggregateWindows(RollingAggregation.max().onColumn(3).overWindow(window));
               Table decWindowAggResults = decSorted.groupBy(0, 4)
                   .aggregateWindows(RollingAggregation.max().onColumn(6).overWindow(window));
               ColumnVector expect = ColumnVector.fromBoxedInts(7, 7, 9, 9, 9, 9, 9, 8, 8, 8, 6, 6);
               ColumnVector decExpect = ColumnVector.decimalFromLongs(2, 7, 7, 9, 9, 9, 9, 9, 8, 8, 8, 6, 6)) {
            assertColumnsAreEqual(expect, windowAggResults.getColumn(0));
            assertColumnsAreEqual(decExpect, decWindowAggResults.getColumn(0));
          }
        }
      }
    }
  }

  @Test
  void testWindowingSum() {
    try (Table unsorted = new Table.TestBuilder()
        .column(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1) // GBY Key
        .column(1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3) // GBY Key
        .column(1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6) // OBY Key
        .column(7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6) // Agg Column
        .build()) {
      try (Table sorted = unsorted.orderBy(OrderByArg.asc(0), OrderByArg.asc(1), OrderByArg.asc(2));
           ColumnVector expectSortedAggColumn = ColumnVector.fromBoxedInts(7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6);
           Scalar two = Scalar.fromInt(2);
           Scalar one = Scalar.fromInt(1)) {
        ColumnVector sortedAggColumn = sorted.getColumn(3);
        assertColumnsAreEqual(expectSortedAggColumn, sortedAggColumn);

        try (WindowOptions window = WindowOptions.builder()
            .minPeriods(1)
            .window(two, one)
            .build()) {

          try (Table windowAggResults = sorted.groupBy(0, 1)
              .aggregateWindows(RollingAggregation.sum().onColumn(3).overWindow(window));
               ColumnVector expectAggResult = ColumnVector.fromBoxedLongs(12L, 13L, 15L, 10L, 16L, 24L, 19L, 10L, 8L, 14L, 12L, 12L)) {
            assertColumnsAreEqual(expectAggResult, windowAggResults.getColumn(0));
          }
        }
      }
    }
  }

  @Test
  void testWindowingRowNumber() {
    try (Table unsorted = new Table.TestBuilder()
        .column(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1) // GBY Key
        .column(1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3) // GBY Key
        .column(1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6) // OBY Key
        .column(7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6) // Agg Column
        .decimal32Column(-1, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3) // Decimal GBY Key
        .decimal64Column(1, 1L, 1L, 2L, 2L, 3L, 3L, 4L, 4L, 5L, 5L, 6L, 6L) // Decimal OBY Key
        .decimal64Column(2, 7L, 5L, 1L, 9L, 7L, 9L, 8L, 2L, 8L, 0L, 6L, 6L) // Decimal Agg Column
        .build()) {
      try (Table sorted = unsorted.orderBy(OrderByArg.asc(0), OrderByArg.asc(1), OrderByArg.asc(2));
           Table decSorted = unsorted.orderBy(OrderByArg.asc(0), OrderByArg.asc(4), OrderByArg.asc(5));
           ColumnVector expectSortedAggColumn = ColumnVector.fromBoxedInts(7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6);
           ColumnVector expectDecSortedAggColumn = ColumnVector.decimalFromLongs(2, 7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6)) {
        ColumnVector sortedAggColumn = sorted.getColumn(3);
        assertColumnsAreEqual(expectSortedAggColumn, sortedAggColumn);
        ColumnVector decSortedAggColumn = decSorted.getColumn(6);
        assertColumnsAreEqual(expectDecSortedAggColumn, decSortedAggColumn);

        WindowOptions.Builder windowBuilder = WindowOptions.builder().minPeriods(1);

        try (Scalar two = Scalar.fromInt(2);
             Scalar one = Scalar.fromInt(1);
             WindowOptions options = windowBuilder.window(two, one).build();
             WindowOptions options1 = windowBuilder.window(two, one).build()) {
          try (Table windowAggResults = sorted.groupBy(0, 1)
              .aggregateWindows(RollingAggregation
                  .rowNumber()
                  .onColumn(3)
                  .overWindow(options));
               Table decWindowAggResults = decSorted.groupBy(0, 4)
                   .aggregateWindows(RollingAggregation
                       .rowNumber()
                       .onColumn(6)
                       .overWindow(options1));
               ColumnVector expectAggResult = ColumnVector.fromBoxedInts(1, 2, 2, 2, 1, 2, 2, 2, 1, 2, 2, 2)) {
            assertColumnsAreEqual(expectAggResult, windowAggResults.getColumn(0));
            assertColumnsAreEqual(expectAggResult, decWindowAggResults.getColumn(0));
          }
        }

        try (Scalar three = Scalar.fromInt(3);
             Scalar two = Scalar.fromInt(2);
             WindowOptions options = windowBuilder.window(three, two).build();
             WindowOptions options1 = windowBuilder.window(three, two).build()) {
          try (Table windowAggResults = sorted.groupBy(0, 1)
              .aggregateWindows(RollingAggregation
                  .rowNumber()
                  .onColumn(3)
                  .overWindow(options));
               Table decWindowAggResults = decSorted.groupBy(0, 4)
                   .aggregateWindows(RollingAggregation
                       .rowNumber()
                       .onColumn(6)
                       .overWindow(options1));
               ColumnVector expectAggResult = ColumnVector.fromBoxedInts(1, 2, 3, 3, 1, 2, 3, 3, 1, 2, 3, 3)) {
            assertColumnsAreEqual(expectAggResult, windowAggResults.getColumn(0));
            assertColumnsAreEqual(expectAggResult, decWindowAggResults.getColumn(0));
          }
        }

        try (Scalar four = Scalar.fromInt(4);
             Scalar three = Scalar.fromInt(3);
             WindowOptions options = windowBuilder.window(four, three).build();
             WindowOptions options1 = windowBuilder.window(four, three).build()) {
          try (Table windowAggResults = sorted.groupBy(0, 1)
              .aggregateWindows(RollingAggregation
                  .rowNumber()
                  .onColumn(3)
                  .overWindow(options));
               Table decWindowAggResults = decSorted.groupBy(0, 4)
                   .aggregateWindows(RollingAggregation
                       .rowNumber()
                       .onColumn(6)
                       .overWindow(options1));
               ColumnVector expectAggResult = ColumnVector.fromBoxedInts(1, 2, 3, 4, 1, 2, 3, 4, 1, 2, 3, 4)) {
            assertColumnsAreEqual(expectAggResult, windowAggResults.getColumn(0));
            assertColumnsAreEqual(expectAggResult, decWindowAggResults.getColumn(0));
          }
        }
       }
    }
  }

  @Test
  void testWindowingCollectList() {
    RollingAggregation aggCollectWithNulls = RollingAggregation.collectList(NullPolicy.INCLUDE);
    RollingAggregation aggCollect = RollingAggregation.collectList();
    try (Scalar two = Scalar.fromInt(2);
         Scalar one = Scalar.fromInt(1);
         WindowOptions winOpts = WindowOptions.builder()
             .minPeriods(1)
             .window(two, one)
             .build()) {
      StructType nestedType = new StructType(false,
          new BasicType(false, DType.INT32), new BasicType(false, DType.STRING));
      try (Table raw = new Table.TestBuilder()
          .column(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1) // GBY Key
          .column(1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3) // GBY Key
          .column(1, 2, 3, 4, 1, 2, 3, 4, 5, 6, 7, 8) // OBY Key
          .column(7, 5, 1, 9, 7, 9, 8, 2, null, 0, 6, null) // Agg Column of INT32
          .column(nestedType,                          // Agg Column of Struct
              new StructData(1, "s1"), new StructData(2, "s2"), new StructData(3, "s3"),
              new StructData(4, "s4"), new StructData(11, "s11"), new StructData(22, "s22"),
              new StructData(33, "s33"), new StructData(44, "s44"), new StructData(111, "s111"),
              new StructData(222, "s222"), new StructData(333, "s333"), new StructData(444, "s444")
          ).build();
           ColumnVector expectSortedAggColumn = ColumnVector
               .fromBoxedInts(7, 5, 1, 9, 7, 9, 8, 2, null, 0, 6, null)) {
        try (Table sorted = raw.orderBy(OrderByArg.asc(0), OrderByArg.asc(1), OrderByArg.asc(2))) {
          ColumnVector sortedAggColumn = sorted.getColumn(3);
          assertColumnsAreEqual(expectSortedAggColumn, sortedAggColumn);

          // Primitive type: INT32
          //  a) including nulls
          try (Table windowAggResults = sorted.groupBy(0, 1)
              .aggregateWindows(aggCollectWithNulls.onColumn(3).overWindow(winOpts));
               ColumnVector expected = ColumnVector.fromLists(
                   new ListType(false, new BasicType(false, DType.INT32)),
                   Arrays.asList(7, 5), Arrays.asList(7, 5, 1), Arrays.asList(5, 1, 9), Arrays.asList(1, 9),
                   Arrays.asList(7, 9), Arrays.asList(7, 9, 8), Arrays.asList(9, 8, 2), Arrays.asList(8, 2),
                   Arrays.asList(null, 0), Arrays.asList(null, 0, 6), Arrays.asList(0, 6, null), Arrays.asList(6, null))) {
            assertColumnsAreEqual(expected, windowAggResults.getColumn(0));
          }
          //  b) excluding nulls
          try (Table windowAggResults = sorted.groupBy(0, 1)
              .aggregateWindows(aggCollect.onColumn(3).overWindow(winOpts));
               ColumnVector expected = ColumnVector.fromLists(
                   new ListType(false, new BasicType(false, DType.INT32)),
                   Arrays.asList(7, 5), Arrays.asList(7, 5, 1), Arrays.asList(5, 1, 9), Arrays.asList(1, 9),
                   Arrays.asList(7, 9), Arrays.asList(7, 9, 8), Arrays.asList(9, 8, 2), Arrays.asList(8, 2),
                   Arrays.asList(0), Arrays.asList(0, 6), Arrays.asList(0, 6), Arrays.asList(6))) {
            assertColumnsAreEqual(expected, windowAggResults.getColumn(0));
          }

          // Nested type: Struct
          List<StructData>[] expectedNestedData = new List[12];
          expectedNestedData[0] = Arrays.asList(new StructData(1, "s1"), new StructData(2, "s2"));
          expectedNestedData[1] = Arrays.asList(new StructData(1, "s1"), new StructData(2, "s2"), new StructData(3, "s3"));
          expectedNestedData[2] = Arrays.asList(new StructData(2, "s2"), new StructData(3, "s3"), new StructData(4, "s4"));
          expectedNestedData[3] = Arrays.asList(new StructData(3, "s3"), new StructData(4, "s4"));
          expectedNestedData[4] = Arrays.asList(new StructData(11, "s11"), new StructData(22, "s22"));
          expectedNestedData[5] = Arrays.asList(new StructData(11, "s11"), new StructData(22, "s22"), new StructData(33, "s33"));
          expectedNestedData[6] = Arrays.asList(new StructData(22, "s22"), new StructData(33, "s33"), new StructData(44, "s44"));
          expectedNestedData[7] = Arrays.asList(new StructData(33, "s33"), new StructData(44, "s44"));
          expectedNestedData[8] = Arrays.asList(new StructData(111, "s111"), new StructData(222, "s222"));
          expectedNestedData[9] = Arrays.asList(new StructData(111, "s111"), new StructData(222, "s222"), new StructData(333, "s333"));
          expectedNestedData[10] = Arrays.asList(new StructData(222, "s222"), new StructData(333, "s333"), new StructData(444, "s444"));
          expectedNestedData[11] = Arrays.asList(new StructData(333, "s333"), new StructData(444, "s444"));
          try (Table windowAggResults = sorted.groupBy(0, 1)
              .aggregateWindows(aggCollect.onColumn(4).overWindow(winOpts));
               ColumnVector expected = ColumnVector.fromLists(
                   new ListType(false, nestedType), expectedNestedData)) {
            assertColumnsAreEqual(expected, windowAggResults.getColumn(0));
          }
        }
      }
    }
  }

  @Test
  void testWindowingCollectSet() {
    RollingAggregation aggCollect = RollingAggregation.collectSet();
    RollingAggregation aggCollectWithEqNulls = RollingAggregation.collectSet(NullPolicy.INCLUDE,
        NullEquality.EQUAL, NaNEquality.UNEQUAL);
    RollingAggregation aggCollectWithUnEqNulls = RollingAggregation.collectSet(NullPolicy.INCLUDE,
        NullEquality.UNEQUAL, NaNEquality.UNEQUAL);
    RollingAggregation aggCollectWithEqNaNs = RollingAggregation.collectSet(NullPolicy.INCLUDE,
        NullEquality.EQUAL, NaNEquality.ALL_EQUAL);

    try (Scalar two = Scalar.fromInt(2);
         Scalar one = Scalar.fromInt(1);
         WindowOptions winOpts = WindowOptions.builder()
             .minPeriods(1)
             .window(two, one)
             .build()) {
      try (Table raw = new Table.TestBuilder()
          .column(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1) // GBY Key
          .column(1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3) // GBY Key
          .column(1, 2, 3, 4, 1, 2, 3, 4, 5, 6, 7, 8) // OBY Key
          .column(5, 5, 1, 1, 1, 4, 3, 4, null, null, 6, 7) // Agg Column of INT32
          .column(1.1, 1.1, null, 2.2, -3.0, 1.3e-7, -3.0, Double.NaN, 1e-3, null, Double.NaN, Double.NaN) // Agg Column of FLOAT64
          .build();
           ColumnVector expectSortedAggColumn = ColumnVector
               .fromBoxedInts(5, 5, 1, 1, 1, 4, 3, 4, null, null, 6, 7)) {
        try (Table sorted = raw.orderBy(OrderByArg.asc(0), OrderByArg.asc(1), OrderByArg.asc(2))) {
          ColumnVector sortedAggColumn = sorted.getColumn(3);
          assertColumnsAreEqual(expectSortedAggColumn, sortedAggColumn);

          // Primitive type: INT32
          //  a) excluding NULLs
          try (Table windowAggResults = sorted.groupBy(0, 1)
              .aggregateWindows(aggCollect.onColumn(3).overWindow(winOpts));
               ColumnVector expected = ColumnVector.fromLists(
                   new ListType(false, new BasicType(false, DType.INT32)),
                   Arrays.asList(5), Arrays.asList(1, 5), Arrays.asList(1, 5), Arrays.asList(1),
                   Arrays.asList(1, 4), Arrays.asList(1, 3, 4), Arrays.asList(3, 4), Arrays.asList(3, 4),
                   Arrays.asList(), Arrays.asList(6), Arrays.asList(6, 7), Arrays.asList(6, 7))) {
            assertColumnsAreEqual(expected, windowAggResults.getColumn(0));
          }
          //  b) including NULLs AND NULLs are equal
          try (Table windowAggResults = sorted.groupBy(0, 1)
              .aggregateWindows(aggCollectWithEqNulls.onColumn(3).overWindow(winOpts));
               ColumnVector expected = ColumnVector.fromLists(
                   new ListType(false, new BasicType(false, DType.INT32)),
                   Arrays.asList(5), Arrays.asList(1, 5), Arrays.asList(1, 5), Arrays.asList(1),
                   Arrays.asList(1, 4), Arrays.asList(1, 3, 4), Arrays.asList(3, 4), Arrays.asList(3, 4),
                   Arrays.asList((Integer) null), Arrays.asList(6, null), Arrays.asList(6, 7, null), Arrays.asList(6, 7))) {
            assertColumnsAreEqual(expected, windowAggResults.getColumn(0));
          }
          //  c) including NULLs AND NULLs are unequal
          try (Table windowAggResults = sorted.groupBy(0, 1)
              .aggregateWindows(aggCollectWithUnEqNulls.onColumn(3).overWindow(winOpts));
               ColumnVector expected = ColumnVector.fromLists(
                   new ListType(false, new BasicType(false, DType.INT32)),
                   Arrays.asList(5), Arrays.asList(1, 5), Arrays.asList(1, 5), Arrays.asList(1),
                   Arrays.asList(1, 4), Arrays.asList(1, 3, 4), Arrays.asList(3, 4), Arrays.asList(3, 4),
                   Arrays.asList(null, null), Arrays.asList(6, null, null), Arrays.asList(6, 7, null), Arrays.asList(6, 7))) {
            assertColumnsAreEqual(expected, windowAggResults.getColumn(0));
          }

          // Primitive type: FLOAT64
          //  a) excluding NULLs
          try (Table windowAggResults = sorted.groupBy(0, 1)
              .aggregateWindows(aggCollect.onColumn(4).overWindow(winOpts));
               ColumnVector expected = ColumnVector.fromLists(
                   new ListType(false, new BasicType(false, DType.FLOAT64)),
                   Arrays.asList(1.1), Arrays.asList(1.1), Arrays.asList(1.1, 2.2), Arrays.asList(2.2),
                   Arrays.asList(-3.0, 1.3e-7), Arrays.asList(-3.0, 1.3e-7),
                   Arrays.asList(-3.0, 1.3e-7, Double.NaN), Arrays.asList(-3.0, Double.NaN),
                   Arrays.asList(1e-3), Arrays.asList(1e-3, Double.NaN),
                   Arrays.asList(Double.NaN, Double.NaN), Arrays.asList(Double.NaN, Double.NaN))) {
            assertColumnsAreEqual(expected, windowAggResults.getColumn(0));
          }
          //  b) including NULLs AND NULLs are equal
          try (Table windowAggResults = sorted.groupBy(0, 1)
              .aggregateWindows(aggCollectWithEqNulls.onColumn(4).overWindow(winOpts));
               ColumnVector expected = ColumnVector.fromLists(
                   new ListType(false, new BasicType(false, DType.FLOAT64)),
                   Arrays.asList(1.1), Arrays.asList(1.1, null), Arrays.asList(1.1, 2.2, null), Arrays.asList(2.2, null),
                   Arrays.asList(-3.0, 1.3e-7), Arrays.asList(-3.0, 1.3e-7),
                   Arrays.asList(-3.0, 1.3e-7, Double.NaN), Arrays.asList(-3.0, Double.NaN),
                   Arrays.asList(1e-3, null), Arrays.asList(1e-3, Double.NaN, null),
                   Arrays.asList(Double.NaN, Double.NaN, null), Arrays.asList(Double.NaN, Double.NaN))) {
            assertColumnsAreEqual(expected, windowAggResults.getColumn(0));
          }
          //  c) including NULLs AND NULLs are equal AND NaNs are equal
          try (Table windowAggResults = sorted.groupBy(0, 1)
              .aggregateWindows(aggCollectWithEqNaNs.onColumn(4).overWindow(winOpts));
               ColumnVector expected = ColumnVector.fromLists(
                   new ListType(false, new BasicType(false, DType.FLOAT64)),
                   Arrays.asList(1.1), Arrays.asList(1.1, null), Arrays.asList(1.1, 2.2, null), Arrays.asList(2.2, null),
                   Arrays.asList(-3.0, 1.3e-7), Arrays.asList(-3.0, 1.3e-7),
                   Arrays.asList(-3.0, 1.3e-7, Double.NaN), Arrays.asList(-3.0, Double.NaN),
                   Arrays.asList(1e-3, null), Arrays.asList(1e-3, Double.NaN, null),
                   Arrays.asList(Double.NaN, null), Arrays.asList(Double.NaN))) {
            assertColumnsAreEqual(expected, windowAggResults.getColumn(0));
          }
        }
      }
    }
  }

  @Test
  void testWindowingLead() {
    try (Table unsorted = new Table.TestBuilder()
        .column(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1) // GBY Key
        .column(1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3) // GBY Key
        .column(1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6) // OBY Key
        .column(7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6) // Int Agg Column
        .decimal32Column(-1, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3) // Decimal GBY Key
        .decimal64Column(1, 1L, 1L, 2L, 2L, 3L, 3L, 4L, 4L, 5L, 5L, 6L, 6L) // Decimal OBY Key
        .decimal64Column(-2, 7L, 5L, 1L, 9L, 7L, 9L, 8L, 2L, 8L, 0L, 6L, 6L) // Decimal Agg Column
        .column(new ListType(false, new BasicType(true, DType.INT32)),
            Arrays.asList(11, 12, null, 13), Arrays.asList(14, null, 15, null), Arrays.asList((Integer) null),  Arrays.asList(16),
            Arrays.asList(21, null, null, 22), Arrays.asList(23, 24), Arrays.asList(25, 26, 27),  Arrays.asList(28, 29, null),
            Arrays.asList(null, 31), Arrays.asList(32, 33, 34), Arrays.asList(35, 36),  Arrays.asList(37, 38, 39)) // List Agg COLUMN
        .column(new StructType(true,
                new BasicType(true, DType.INT32),
                new BasicType(true, DType.STRING)),
            new StructData(1, "s1"), new StructData(null, "s2"), new StructData(2, null), new StructData(3, "s3"),
            new StructData(11, "s11"), null, new StructData(13, "s13"), new StructData(14, "s14"),
            new StructData(111, "s111"), new StructData(null, "s112"), new StructData(2, "s222"), new StructData(3, "s333")) //STRUCT Agg COLUMN
        .build()) {

      try (Table sorted = unsorted.orderBy(OrderByArg.asc(0), OrderByArg.asc(1), OrderByArg.asc(2));
           Table decSorted = unsorted.orderBy(OrderByArg.asc(0), OrderByArg.asc(4), OrderByArg.asc(5));
           ColumnVector expectSortedAggColumn = ColumnVector.fromBoxedInts(7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6);
           ColumnVector expectDecSortedAggColumn = ColumnVector.decimalFromLongs(-2, 7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6)) {
        ColumnVector sortedAggColumn = sorted.getColumn(3);
        assertColumnsAreEqual(expectSortedAggColumn, sortedAggColumn);
        ColumnVector decSortedAggColumn = decSorted.getColumn(6);
        assertColumnsAreEqual(expectDecSortedAggColumn, decSortedAggColumn);

        WindowOptions.Builder windowBuilder = WindowOptions.builder().minPeriods(1);

        try (Scalar two = Scalar.fromInt(2);
             Scalar one = Scalar.fromInt(1);
             WindowOptions options = windowBuilder.window(two, one).build();
             Table windowAggResults = sorted.groupBy(0, 1)
                 .aggregateWindows(RollingAggregation
                     .lead(0)
                     .onColumn(3) // Int Agg Column
                     .overWindow(options));
             Table decWindowAggResults = decSorted.groupBy(0, 4)
                 .aggregateWindows(RollingAggregation
                     .lead(0)
                     .onColumn(6) // Decimal Agg Column
                     .overWindow(options));
             Table listWindowAggResults = sorted.groupBy(0, 1).aggregateWindows(
                 RollingAggregation
                     .lead(0)
                     .onColumn(7) // List Agg COLUMN
                     .overWindow(options));
             Table structWindowAggResults = sorted.groupBy(0, 1).aggregateWindows(
                 RollingAggregation
                     .lead(0)
                     .onColumn(8) //STRUCT Agg COLUMN
                     .overWindow(options));
             ColumnVector expectAggResult = ColumnVector.fromBoxedInts(7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6);
             ColumnVector decExpectAggResult = ColumnVector.decimalFromLongs(-2, 7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6);
             ColumnVector listExpectAggResult = ColumnVector.fromLists(
                 new HostColumnVector.ListType(true, new HostColumnVector.BasicType(true, DType.INT32)),
                 Arrays.asList(11, 12, null, 13), Arrays.asList(14, null, 15, null), Arrays.asList((Integer) null), Arrays.asList(16),
                 Arrays.asList(21, null, null, 22), Arrays.asList(23, 24), Arrays.asList(25, 26, 27), Arrays.asList(28, 29, null),
                 Arrays.asList(null, 31), Arrays.asList(32, 33, 34), Arrays.asList(35, 36), Arrays.asList(37, 38, 39));
             ColumnVector structExpectAggResult = ColumnVector.fromStructs(
                 new StructType(true,
                     new BasicType(true, DType.INT32),
                     new BasicType(true, DType.STRING)),
                 new StructData(1, "s1"), new StructData(null, "s2"), new StructData(2, null), new StructData(3, "s3"),
                 new StructData(11, "s11"), null, new StructData(13, "s13"), new StructData(14, "s14"),
                 new StructData(111, "s111"), new StructData(null, "s112"), new StructData(2, "s222"), new StructData(3, "s333"))) {

          assertColumnsAreEqual(expectAggResult, windowAggResults.getColumn(0));
          assertColumnsAreEqual(decExpectAggResult, decWindowAggResults.getColumn(0));
          assertColumnsAreEqual(listExpectAggResult, listWindowAggResults.getColumn(0));
          assertColumnsAreEqual(structExpectAggResult, structWindowAggResults.getColumn(0));
        }

        try (Scalar zero = Scalar.fromInt(0);
             Scalar one = Scalar.fromInt(1);
             WindowOptions options = windowBuilder.window(zero, one).build();
             Table windowAggResults = sorted.groupBy(0, 1)
                 .aggregateWindows(RollingAggregation
                     .lead(1)
                     .onColumn(3) //Int Agg COLUMN
                     .overWindow(options));
             Table decWindowAggResults = sorted.groupBy(0, 4)
                 .aggregateWindows(RollingAggregation
                     .lead(1)
                     .onColumn(6) //Decimal Agg COLUMN
                     .overWindow(options));
             Table listWindowAggResults = sorted.groupBy(0, 1).aggregateWindows(
                 RollingAggregation
                     .lead(1)
                     .onColumn(7) //LIST Agg COLUMN
                     .overWindow(options));
             Table structWindowAggResults = sorted.groupBy(0, 1).aggregateWindows(
                 RollingAggregation
                     .lead(1)
                     .onColumn(8) //STRUCT Agg COLUMN
                     .overWindow(options));
             ColumnVector expectAggResult = ColumnVector.fromBoxedInts(5, 1, 9, null, 9, 8, 2, null, 0, 6, 6, null);
             ColumnVector decExpectAggResult = decimalFromBoxedInts(true, -2, 5, 1, 9, null, 9, 8, 2, null, 0, 6, 6, null);
             ColumnVector listExpectAggResult = ColumnVector.fromLists(
                 new HostColumnVector.ListType(true, new HostColumnVector.BasicType(true, DType.INT32)),
                 Arrays.asList(14, null, 15, null), Arrays.asList((Integer) null), Arrays.asList(16), null,
                 Arrays.asList(23, 24), Arrays.asList(25, 26, 27), Arrays.asList(28, 29, null), null,
                 Arrays.asList(32, 33, 34), Arrays.asList(35, 36), Arrays.asList(37, 38, 39), null);
             ColumnVector structExpectAggResult = ColumnVector.fromStructs(
                 new StructType(true,
                     new BasicType(true, DType.INT32),
                     new BasicType(true, DType.STRING)),
                 new StructData(null, "s2"), new StructData(2, null), new StructData(3, "s3"), null,
                 null, new StructData(13, "s13"), new StructData(14, "s14"), null,
                 new StructData(null, "s112"), new StructData(2, "s222"), new StructData(3, "s333"), null)) {
          assertColumnsAreEqual(expectAggResult, windowAggResults.getColumn(0));
          assertColumnsAreEqual(decExpectAggResult, decWindowAggResults.getColumn(0));
          assertColumnsAreEqual(listExpectAggResult, listWindowAggResults.getColumn(0));
          assertColumnsAreEqual(structExpectAggResult, structWindowAggResults.getColumn(0));
        }

        try (Scalar zero = Scalar.fromInt(0);
             Scalar one = Scalar.fromInt(1);
             WindowOptions options = windowBuilder.window(zero, one).build();
             ColumnVector defaultOutput = ColumnVector.fromBoxedInts(0, -1, -2, -3, -4, -5, -6, -7, -8, -9, -10, -11);
             ColumnVector decDefaultOutput = ColumnVector.decimalFromLongs(-2, 0, -1, -2, -3, -4, -5, -6, -7, -8, -9, -10, -11);
             ColumnVector listDefaultOutput = ColumnVector.fromLists(
                 new HostColumnVector.ListType(true, new HostColumnVector.BasicType(true, DType.INT32)),
                 Arrays.asList(111), Arrays.asList(222), Arrays.asList(333), Arrays.asList(444, null, 555),
                 Arrays.asList(-11), Arrays.asList(-22), Arrays.asList(-33), Arrays.asList(-44),
                 Arrays.asList(6), Arrays.asList(6), Arrays.asList(6), Arrays.asList(6, null, null));
             ColumnVector structDefaultOutput = ColumnVector.fromStructs(
                 new StructType(true,
                     new BasicType(true, DType.INT32),
                     new BasicType(true, DType.STRING)),
                 new StructData(-1, "s1"), new StructData(null, "s2"), new StructData(-2, null), new StructData(-3, "s3"),
                 new StructData(-11, "s11"), null, new StructData(-13, "s13"), new StructData(-14, "s14"),
                 new StructData(-111, "s111"), new StructData(null, "s112"), new StructData(-222, "s222"), new StructData(-333, "s333"));

             Table windowAggResults = sorted.groupBy(0, 1)
                 .aggregateWindows(RollingAggregation
                     .lead(1, defaultOutput)
                     .onColumn(3) //Int Agg COLUMN
                     .overWindow(options));
             Table decWindowAggResults = sorted.groupBy(0, 4)
                 .aggregateWindows(RollingAggregation
                     .lead(1, decDefaultOutput)
                     .onColumn(6) //Decimal Agg COLUMN
                     .overWindow(options));
             Table listWindowAggResults = sorted.groupBy(0, 1).aggregateWindows(
                 RollingAggregation
                     .lead(1, listDefaultOutput)
                     .onColumn(7) //LIST Agg COLUMN
                     .overWindow(options));
             Table structWindowAggResults = sorted.groupBy(0, 1).aggregateWindows(
                 RollingAggregation
                     .lead(1, structDefaultOutput)
                     .onColumn(8) //STRUCT Agg COLUMN
                     .overWindow(options));
             ColumnVector expectAggResult = ColumnVector.fromBoxedInts(5, 1, 9, -3, 9, 8, 2, -7, 0, 6, 6, -11);
             ColumnVector decExpectAggResult = ColumnVector.decimalFromLongs(-2, 5, 1, 9, -3, 9, 8, 2, -7, 0, 6, 6, -11);
             ColumnVector listExpectAggResult = ColumnVector.fromLists(
                 new HostColumnVector.ListType(true, new HostColumnVector.BasicType(true, DType.INT32)),
                 Arrays.asList(14, null, 15, null), Arrays.asList((Integer) null), Arrays.asList(16), Arrays.asList(444, null, 555),
                 Arrays.asList(23, 24), Arrays.asList(25, 26, 27), Arrays.asList(28, 29, null), Arrays.asList(-44),
                 Arrays.asList(32, 33, 34), Arrays.asList(35, 36), Arrays.asList(37, 38, 39), Arrays.asList(6, null, null));
             ColumnVector structExpectAggResult = ColumnVector.fromStructs(
                 new StructType(true,
                     new BasicType(true, DType.INT32),
                     new BasicType(true, DType.STRING)),
                 new StructData(null, "s2"), new StructData(2, null), new StructData(3, "s3"), new StructData(-3, "s3"),
                 null, new StructData(13, "s13"), new StructData(14, "s14"), new StructData(-14, "s14"),
                 new StructData(null, "s112"), new StructData(2, "s222"), new StructData(3, "s333"), new StructData(-333, "s333"))) {
          assertColumnsAreEqual(expectAggResult, windowAggResults.getColumn(0));
          assertColumnsAreEqual(decExpectAggResult, decWindowAggResults.getColumn(0));
          assertColumnsAreEqual(listExpectAggResult, listWindowAggResults.getColumn(0));
          assertColumnsAreEqual(structExpectAggResult, structWindowAggResults.getColumn(0));
        }

        // Outside bounds
        try (Scalar zero = Scalar.fromInt(0);
             Scalar one = Scalar.fromInt(1);
             WindowOptions options = windowBuilder.window(zero, one).build();
             Table windowAggResults = sorted.groupBy(0, 1)
                 .aggregateWindows(RollingAggregation
                     .lead(3)
                     .onColumn(3) //Int Agg COLUMN
                     .overWindow(options));
             Table decWindowAggResults = sorted.groupBy(0, 4)
                 .aggregateWindows(RollingAggregation
                     .lead(3)
                     .onColumn(6) //Decimal Agg COLUMN
                     .overWindow(options));
             Table listWindowAggResults = sorted.groupBy(0, 1).aggregateWindows(
                 RollingAggregation
                     .lead(3)
                     .onColumn(7) //LIST Agg COLUMN
                     .overWindow(options));
             Table structWindowAggResults = sorted.groupBy(0, 1).aggregateWindows(
                 RollingAggregation
                     .lead(3)
                     .onColumn(8) //STRUCT Agg COLUMN
                     .overWindow(options));
             ColumnVector expectAggResult = ColumnVector.fromBoxedInts(null, null, null, null, null, null, null, null, null, null, null, null);
             ColumnVector decExpectAggResult = decimalFromBoxedInts(true, -2, null, null, null, null, null, null, null, null, null, null, null, null);
             ColumnVector listExpectAggResult = ColumnVector.fromLists(
                 new HostColumnVector.ListType(true, new HostColumnVector.BasicType(true, DType.INT32)),
                 null, null, null, null, null, null, null, null, null, null, null, null);
             ColumnVector structExpectAggResult = ColumnVector.fromStructs(
                 new StructType(true,
                     new BasicType(true, DType.INT32),
                     new BasicType(true, DType.STRING)),
                 null, null, null, null, null, null, null, null, null, null, null, null)){
          assertColumnsAreEqual(expectAggResult, windowAggResults.getColumn(0));
          assertColumnsAreEqual(decExpectAggResult, decWindowAggResults.getColumn(0));
          assertColumnsAreEqual(listExpectAggResult, listWindowAggResults.getColumn(0));
          assertColumnsAreEqual(structExpectAggResult, structWindowAggResults.getColumn(0));
        }
      }
    }
  }

  @Test
  void testWindowingLag() {
    try (Table unsorted = new Table.TestBuilder()
        .column(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1) // GBY Key
        .column(1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3) // GBY Key
        .column(1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6) // OBY Key
        .column(7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6) // Agg Column
        .decimal32Column(-1, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3) // Decimal GBY Key
        .decimal64Column(1, 1L, 1L, 2L, 2L, 3L, 3L, 4L, 4L, 5L, 5L, 6L, 6L) // Decimal OBY Key
        .decimal64Column(-2, 7L, 5L, 1L, 9L, 7L, 9L, 8L, 2L, 8L, 0L, 6L, 6L) // Decimal Agg Column
        .column(new ListType(false, new BasicType(true, DType.INT32)),
            Arrays.asList(11, 12, null, 13), Arrays.asList(14, null, 15, null), Arrays.asList((Integer) null),  Arrays.asList(16),
            Arrays.asList(21, null, null, 22), Arrays.asList(23, 24), Arrays.asList(25, 26, 27),  Arrays.asList(28, 29, null),
            Arrays.asList(null, 31), Arrays.asList(32, 33, 34), Arrays.asList(35, 36),  Arrays.asList(37, 38, 39)) // List Agg COLUMN
        .column(new StructType(true,
                new BasicType(true, DType.INT32),
                new BasicType(true, DType.STRING)),
            new StructData(1, "s1"), new StructData(null, "s2"), new StructData(2, null), new StructData(3, "s3"),
            new StructData(11, "s11"), null, new StructData(13, "s13"), new StructData(14, "s14"),
            new StructData(111, "s111"), new StructData(null, "s112"), new StructData(2, "s222"), new StructData(3, "s333")) //STRUCT Agg COLUMN
        .build()) {

      try (Table sorted = unsorted.orderBy(OrderByArg.asc(0), OrderByArg.asc(1), OrderByArg.asc(2));
           Table decSorted = unsorted.orderBy(OrderByArg.asc(0), OrderByArg.asc(4), OrderByArg.asc(5));
           ColumnVector expectSortedAggColumn = ColumnVector.fromBoxedInts(7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6);
           ColumnVector decExpectSortedAggColumn = ColumnVector.decimalFromLongs(-2, 7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6)) {
        ColumnVector sortedAggColumn = sorted.getColumn(3);
        assertColumnsAreEqual(expectSortedAggColumn, sortedAggColumn);
        ColumnVector decSortedAggColumn = decSorted.getColumn(6);
        assertColumnsAreEqual(decExpectSortedAggColumn, decSortedAggColumn);

        WindowOptions.Builder windowBuilder = WindowOptions.builder().minPeriods(1);

        try (Scalar two = Scalar.fromInt(2);
             Scalar one = Scalar.fromInt(1);
             WindowOptions options = windowBuilder.window(two, one).build();
             Table windowAggResults = sorted.groupBy(0, 1)
                 .aggregateWindows(RollingAggregation
                     .lag(0)
                     .onColumn(3) //Int Agg COLUMN
                     .overWindow(options));
             Table decWindowAggResults = sorted.groupBy(0, 4)
                 .aggregateWindows(RollingAggregation
                     .lag(0)
                     .onColumn(6) //Decimal Agg COLUMN
                     .overWindow(options));
             Table listWindowAggResults = sorted.groupBy(0, 1).aggregateWindows(
                 RollingAggregation
                     .lag(0)
                     .onColumn(7) //LIST Agg COLUMN
                     .overWindow(options));
             Table structWindowAggResults = sorted.groupBy(0, 1).aggregateWindows(
                 RollingAggregation
                     .lag(0)
                     .onColumn(8) //STRUCT Agg COLUMN
                     .overWindow(options));
             ColumnVector expectAggResult = ColumnVector.fromBoxedInts(7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6);
             ColumnVector decExpectAggResult = ColumnVector.decimalFromLongs(-2, 7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6);
             ColumnVector listExpectAggResult = ColumnVector.fromLists(
                 new HostColumnVector.ListType(true, new HostColumnVector.BasicType(true, DType.INT32)),
                 Arrays.asList(11, 12, null, 13), Arrays.asList(14, null, 15, null), Arrays.asList((Integer) null), Arrays.asList(16),
                 Arrays.asList(21, null, null, 22), Arrays.asList(23, 24), Arrays.asList(25, 26, 27), Arrays.asList(28, 29, null),
                 Arrays.asList(null, 31), Arrays.asList(32, 33, 34), Arrays.asList(35, 36), Arrays.asList(37, 38, 39));
             ColumnVector structExpectAggResult = ColumnVector.fromStructs(
                 new StructType(true,
                     new BasicType(true, DType.INT32),
                     new BasicType(true, DType.STRING)),
                 new StructData(1, "s1"), new StructData(null, "s2"), new StructData(2, null), new StructData(3, "s3"),
                 new StructData(11, "s11"), null, new StructData(13, "s13"), new StructData(14, "s14"),
                 new StructData(111, "s111"), new StructData(null, "s112"), new StructData(2, "s222"), new StructData(3, "s333"))) {
          assertColumnsAreEqual(expectAggResult, windowAggResults.getColumn(0));
          assertColumnsAreEqual(decExpectAggResult, decWindowAggResults.getColumn(0));
          assertColumnsAreEqual(listExpectAggResult, listWindowAggResults.getColumn(0));
          assertColumnsAreEqual(structExpectAggResult, structWindowAggResults.getColumn(0));
        }

        try (Scalar zero = Scalar.fromInt(0);
             Scalar two = Scalar.fromInt(2);
             WindowOptions options = windowBuilder.window(two, zero).build();
             Table windowAggResults = sorted.groupBy(0, 1)
                 .aggregateWindows(RollingAggregation
                     .lag(1)
                     .onColumn(3) //Int Agg COLUMN
                     .overWindow(options));
             Table decWindowAggResults = sorted.groupBy(0, 4)
                 .aggregateWindows(RollingAggregation
                     .lag(1)
                     .onColumn(6) //Decimal Agg COLUMN
                     .overWindow(options));
             Table listWindowAggResults = sorted.groupBy(0, 1).aggregateWindows(
                 RollingAggregation
                     .lag(1)
                     .onColumn(7) //LIST Agg COLUMN
                     .overWindow(options));
             Table structWindowAggResults = sorted.groupBy(0, 1).aggregateWindows(
                 RollingAggregation
                     .lag(1)
                     .onColumn(8) //STRUCT Agg COLUMN
                     .overWindow(options));
             ColumnVector expectAggResult = ColumnVector.fromBoxedInts(null, 7, 5, 1, null, 7, 9, 8, null, 8, 0, 6);
             ColumnVector decExpectAggResult = decimalFromBoxedInts(true, -2, null, 7, 5, 1, null, 7, 9, 8, null, 8, 0, 6);
             ColumnVector listExpectAggResult = ColumnVector.fromLists(
                 new HostColumnVector.ListType(true, new HostColumnVector.BasicType(true, DType.INT32)),
                 null, Arrays.asList(11, 12, null, 13), Arrays.asList(14, null, 15, null), Arrays.asList((Integer) null),
                 null, Arrays.asList(21, null, null, 22), Arrays.asList(23, 24), Arrays.asList(25, 26, 27),
                 null, Arrays.asList(null, 31), Arrays.asList(32, 33, 34), Arrays.asList(35, 36));
             ColumnVector structExpectAggResult = ColumnVector.fromStructs(
                 new StructType(true,
                     new BasicType(true, DType.INT32),
                     new BasicType(true, DType.STRING)),
                 null, new StructData(1, "s1"), new StructData(null, "s2"), new StructData(2, null),
                 null, new StructData(11, "s11"), null, new StructData(13, "s13"),
                 null, new StructData(111, "s111"), new StructData(null, "s112"), new StructData(2, "s222"))) {
          assertColumnsAreEqual(expectAggResult, windowAggResults.getColumn(0));
          assertColumnsAreEqual(decExpectAggResult, decWindowAggResults.getColumn(0));
          assertColumnsAreEqual(listExpectAggResult, listWindowAggResults.getColumn(0));
          assertColumnsAreEqual(structExpectAggResult, structWindowAggResults.getColumn(0));
        }

        try (Scalar zero = Scalar.fromInt(0);
             Scalar two = Scalar.fromInt(2);
             WindowOptions options = windowBuilder.window(two, zero).build();
             ColumnVector defaultOutput = ColumnVector.fromBoxedInts(0, -1, -2, -3, -4, -5, -6, -7, -8, -9, -10, -11);
             ColumnVector decDefaultOutput = ColumnVector.decimalFromLongs(-2, 0, -1, -2, -3, -4, -5, -6, -7, -8, -9, -10, -11);
             ColumnVector listDefaultOutput = ColumnVector.fromLists(
                 new HostColumnVector.ListType(true, new HostColumnVector.BasicType(true, DType.INT32)),
                 Arrays.asList(111), Arrays.asList(222), Arrays.asList(333), Arrays.asList(444, null, 555),
                 Arrays.asList(-11), Arrays.asList(-22), Arrays.asList(-33), Arrays.asList(-44),
                 Arrays.asList(6), Arrays.asList(6), Arrays.asList(6), Arrays.asList(6, null, null));
             ColumnVector structDefaultOutput = ColumnVector.fromStructs(
                 new StructType(true,
                     new BasicType(true, DType.INT32),
                     new BasicType(true, DType.STRING)),
                 new StructData(-1, "s1"), new StructData(null, "s2"), new StructData(-2, null), new StructData(-3, "s3"),
                 new StructData(-11, "s11"), null, new StructData(-13, "s13"), new StructData(-14, "s14"),
                 new StructData(-111, "s111"), new StructData(null, "s112"), new StructData(-222, "s222"), new StructData(-333, "s333"));
             Table windowAggResults = sorted.groupBy(0, 1)
                 .aggregateWindows(RollingAggregation
                     .lag(1, defaultOutput)
                     .onColumn(3) //Int Agg COLUMN
                     .overWindow(options));
             Table decWindowAggResults = sorted.groupBy(0, 4)
                 .aggregateWindows(RollingAggregation
                     .lag(1, decDefaultOutput)
                     .onColumn(6) //Decimal Agg COLUMN
                     .overWindow(options));
             Table listWindowAggResults = sorted.groupBy(0, 1).aggregateWindows(
                 RollingAggregation
                     .lag(1, listDefaultOutput)
                     .onColumn(7) //LIST Agg COLUMN
                     .overWindow(options));
             Table structWindowAggResults = sorted.groupBy(0, 1).aggregateWindows(
                 RollingAggregation
                     .lag(1, structDefaultOutput)
                     .onColumn(8) //STRUCT Agg COLUMN
                     .overWindow(options));
             ColumnVector expectAggResult = ColumnVector.fromBoxedInts(0, 7, 5, 1, -4, 7, 9, 8, -8, 8, 0, 6);
             ColumnVector decExpectAggResult = ColumnVector.decimalFromLongs(-2, 0, 7, 5, 1, -4, 7, 9, 8, -8, 8, 0, 6);
             ColumnVector listExpectAggResult = ColumnVector.fromLists(
                 new HostColumnVector.ListType(true, new HostColumnVector.BasicType(true, DType.INT32)),
                 Arrays.asList(111), Arrays.asList(11, 12, null, 13), Arrays.asList(14, null, 15, null), Arrays.asList((Integer) null),
                 Arrays.asList(-11), Arrays.asList(21, null, null, 22), Arrays.asList(23, 24), Arrays.asList(25, 26, 27),
                 Arrays.asList(6), Arrays.asList(null, 31), Arrays.asList(32, 33, 34), Arrays.asList(35, 36));
             ColumnVector structExpectAggResult = ColumnVector.fromStructs(
                 new StructType(true,
                     new BasicType(true, DType.INT32),
                     new BasicType(true, DType.STRING)),
                 new StructData(-1, "s1"), new StructData(1, "s1"), new StructData(null, "s2"), new StructData(2, null),
                 new StructData(-11, "s11"), new StructData(11, "s11"), null, new StructData(13, "s13"),
                 new StructData(-111, "s111"), new StructData(111, "s111"), new StructData(null, "s112"), new StructData(2, "s222"))) {
          assertColumnsAreEqual(expectAggResult, windowAggResults.getColumn(0));
          assertColumnsAreEqual(decExpectAggResult, decWindowAggResults.getColumn(0));
          assertColumnsAreEqual(listExpectAggResult, listWindowAggResults.getColumn(0));
          assertColumnsAreEqual(structExpectAggResult, structWindowAggResults.getColumn(0));
        }

        // Outside bounds
        try (Scalar zero = Scalar.fromInt(0);
             Scalar one = Scalar.fromInt(1);
             WindowOptions options = windowBuilder.window(one, zero).build();
             Table windowAggResults = sorted.groupBy(0, 1)
                 .aggregateWindows(RollingAggregation
                     .lag(3)
                     .onColumn(3) //Int Agg COLUMN
                     .overWindow(options));
             Table decWindowAggResults = sorted.groupBy(0, 4)
                 .aggregateWindows(RollingAggregation
                     .lag(3)
                     .onColumn(6) //Decimal Agg COLUMN
                     .overWindow(options));
             Table listWindowAggResults = sorted.groupBy(0, 1).aggregateWindows(
                 RollingAggregation
                     .lag(3)
                     .onColumn(7) //LIST Agg COLUMN
                     .overWindow(options));
             Table structWindowAggResults = sorted.groupBy(0, 1).aggregateWindows(
                 RollingAggregation
                     .lag(3)
                     .onColumn(8) //STRUCT Agg COLUMN
                     .overWindow(options));
             ColumnVector expectAggResult = ColumnVector.fromBoxedInts(null, null, null, null, null, null, null, null, null, null, null, null);
             ColumnVector decExpectAggResult = decimalFromBoxedInts(true, -2, null, null, null, null, null, null, null, null, null, null, null, null);
             ColumnVector listExpectAggResult = ColumnVector.fromLists(
                 new HostColumnVector.ListType(true, new HostColumnVector.BasicType(true, DType.INT32)),
                 null, null, null, null, null, null, null, null, null, null, null, null);
             ColumnVector structExpectAggResult = ColumnVector.fromStructs(
                 new StructType(true,
                     new BasicType(true, DType.INT32),
                     new BasicType(true, DType.STRING)),
                 null, null, null, null, null, null, null, null, null, null, null, null);) {
          assertColumnsAreEqual(expectAggResult, windowAggResults.getColumn(0));
          assertColumnsAreEqual(decExpectAggResult, decWindowAggResults.getColumn(0));
          assertColumnsAreEqual(listExpectAggResult, listWindowAggResults.getColumn(0));
          assertColumnsAreEqual(structExpectAggResult, structWindowAggResults.getColumn(0));
        }
      }
    }
  }

  @Test
  void testWindowingMean() {
    try (Table unsorted = new Table.TestBuilder().column( 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1) // GBY Key
        .column( 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3) // GBY Key
        .column( 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6) // OBY Key
        .column( 7, 5, 3, 7, 7, 9, 8, 4, 8, 0, 4, 8) // Agg Column
        .build()) {
      try (Table sorted = unsorted.orderBy(OrderByArg.asc(0), OrderByArg.asc(1), OrderByArg.asc(2));
           ColumnVector expectedSortedAggCol = ColumnVector.fromBoxedInts(7, 5, 3, 7, 7, 9, 8, 4, 8, 0, 4, 8)) {
        ColumnVector sortedAggColumn = sorted.getColumn(3);
        assertColumnsAreEqual(expectedSortedAggCol, sortedAggColumn);

        try (Scalar one = Scalar.fromInt(1);
             Scalar two = Scalar.fromInt(2);
             WindowOptions window = WindowOptions.builder()
                 .minPeriods(1)
                 .window(two, one)
                 .build()) {

          try (Table windowAggResults = sorted.groupBy(0, 1)
              .aggregateWindows(RollingAggregation.mean().onColumn(3).overWindow(window));
               ColumnVector expect = ColumnVector.fromBoxedDoubles(6.0d, 5.0d, 5.0d, 5.0d, 8.0d, 8.0d, 7.0d, 6.0d, 4.0d, 4.0d, 4.0d, 6.0d)) {
            assertColumnsAreEqual(expect, windowAggResults.getColumn(0));
          }
        }
      }
    }
  }

  @Test
  void testWindowingOnMultipleDifferentColumns() {
    try (Table unsorted = new Table.TestBuilder()
        .column( 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1) // GBY Key
        .column( 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3) // GBY Key
        .column( 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6) // OBY Key
        .column( 7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6) // Agg Column
        .build()) {
      try (Table sorted = unsorted.orderBy(OrderByArg.asc(0), OrderByArg.asc(1), OrderByArg.asc(2));
           ColumnVector expectedSortedAggCol = ColumnVector.fromBoxedInts(7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6)) {
        ColumnVector sortedAggColumn = sorted.getColumn(3);
        assertColumnsAreEqual(expectedSortedAggCol, sortedAggColumn);

        try (Scalar one = Scalar.fromInt(1);
             Scalar two = Scalar.fromInt(2);
             Scalar three = Scalar.fromInt(3);
             // Window (1,1), with a minimum of 1 reading.
             WindowOptions window_1 = WindowOptions.builder()
                 .minPeriods(1)
                 .window(two, one)
                 .build();

             // Window (2,2), with a minimum of 2 readings.
             WindowOptions window_2 = WindowOptions.builder()
                 .minPeriods(2)
                 .window(three, two)
                 .build();

             // Window (1,1), with a minimum of 3 readings.
             WindowOptions window_3 = WindowOptions.builder()
                 .minPeriods(3)
                 .window(two, one)
                 .build()) {

          try (Table windowAggResults = sorted.groupBy(0, 1)
              .aggregateWindows(
                  RollingAggregation.sum().onColumn(3).overWindow(window_1),
                  RollingAggregation.max().onColumn(3).overWindow(window_1),
                  RollingAggregation.sum().onColumn(3).overWindow(window_2),
                  RollingAggregation.min().onColumn(2).overWindow(window_3)
              );
               ColumnVector expect_0 = ColumnVector.fromBoxedLongs(12L, 13L, 15L, 10L, 16L, 24L, 19L, 10L, 8L, 14L, 12L, 12L);
               ColumnVector expect_1 = ColumnVector.fromBoxedInts(7, 7, 9, 9, 9, 9, 9, 8, 8, 8, 6, 6);
               ColumnVector expect_2 = ColumnVector.fromBoxedLongs(13L, 22L, 22L, 15L, 24L, 26L, 26L, 19L, 14L, 20L, 20L, 12L);
               ColumnVector expect_3 = ColumnVector.fromBoxedInts(null, 1, 1, null, null, 3, 3, null, null, 5, 5, null)) {
            assertColumnsAreEqual(expect_0, windowAggResults.getColumn(0));
            assertColumnsAreEqual(expect_1, windowAggResults.getColumn(1));
            assertColumnsAreEqual(expect_2, windowAggResults.getColumn(2));
            assertColumnsAreEqual(expect_3, windowAggResults.getColumn(3));
          }
        }
      }
    }
  }

  @Test
  void testWindowingWithoutGroupByColumns() {
    try (Table unsorted = new Table.TestBuilder().column( 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6) // OBY Key
        .column( 7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6) // Agg Column
        .build();
         ColumnVector expectSortedAggColumn = ColumnVector.fromBoxedInts(7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6)) {

      try (Table sorted = unsorted.orderBy(OrderByArg.asc(0))) {
        ColumnVector sortedAggColumn = sorted.getColumn(1);
        assertColumnsAreEqual(expectSortedAggColumn, sortedAggColumn);

        try (Scalar one = Scalar.fromInt(1);
             Scalar two = Scalar.fromInt(2);
             WindowOptions window = WindowOptions.builder()
                 .minPeriods(1)
                 .window(two, one)
                 .build()) {

          try (Table windowAggResults = sorted.groupBy().aggregateWindows(
              RollingAggregation.sum().onColumn(1).overWindow(window));
               ColumnVector expectAggResult = ColumnVector.fromBoxedLongs(12L, 13L, 15L, 17L, 25L, 24L, 19L, 18L, 10L, 14L, 12L, 12L)
          ) {
            assertColumnsAreEqual(expectAggResult, windowAggResults.getColumn(0));
          }
        }
      }
    }
  }

  private Scalar getScalar(DType type, long value) {
    if (type.equals(DType.INT32)) {
      return Scalar.fromInt((int) value);
    } else if (type.equals(DType.INT64)) {
      return Scalar.fromLong(value);
    } else if (type.equals(DType.INT16)) {
      return Scalar.fromShort((short) value);
    } else if (type.equals(DType.INT8)) {
      return Scalar.fromByte((byte) value);
    } else if (type.equals(DType.UINT8)) {
      return Scalar.fromUnsignedByte((byte) value);
    } else if (type.equals(DType.UINT16)) {
      return Scalar.fromUnsignedShort((short) value);
    } else if (type.equals(DType.UINT32)) {
      return Scalar.fromUnsignedInt((int) value);
    } else if (type.equals(DType.UINT64)) {
      return Scalar.fromUnsignedLong(value);
    } else if (type.equals(DType.TIMESTAMP_DAYS)) {
      return Scalar.durationFromLong(DType.DURATION_DAYS, value);
    } else if (type.equals(DType.TIMESTAMP_SECONDS)) {
      return Scalar.durationFromLong(DType.DURATION_SECONDS, value);
    } else if (type.equals(DType.TIMESTAMP_MILLISECONDS)) {
      return Scalar.durationFromLong(DType.DURATION_MILLISECONDS, value);
    } else if (type.equals(DType.TIMESTAMP_MICROSECONDS)) {
      return Scalar.durationFromLong(DType.DURATION_MICROSECONDS, value);
    } else if (type.equals(DType.TIMESTAMP_NANOSECONDS)) {
      return Scalar.durationFromLong(DType.DURATION_NANOSECONDS, value);
    } else {
      return Scalar.fromNull(type);
    }
  }

  @Test
  void testRangeWindowingCount() {
    try (
        Table unsorted = new Table.TestBuilder()
            .column(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1) // GBY Key
            .column(0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 2) // GBY Key
            .column(7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6, 8) // Agg Column
            .column(1L, 1L, 2L, 3L, 3L, 3L, 4L, 4L, 5L, 5L, 6L, 6L, 7L) // orderBy Key
            .column((short) 1, (short)1, (short)2, (short)3, (short)3, (short)3, (short)4, (short)4, (short)5, (short)5, (short)6, (short)6, (short)7) // orderBy Key
            .column(1, 1, 2, 3, 3, 3, 4, 4, 5, 5, 6, 6, 7) // orderBy Key
            .column((byte) 1, (byte)1, (byte)2, (byte)3, (byte)3, (byte)3, (byte)4, (byte)4, (byte)5, (byte)5, (byte)6, (byte)6, (byte)7) // orderBy Key
            .timestampDayColumn(1, 1, 2, 3, 3, 3, 4, 4, 5, 5, 6, 6, 7) // timestamp orderBy Key
            .timestampSecondsColumn(1L, 1L, 2L, 3L, 3L, 3L, 4L, 4L, 5L, 5L, 6L, 6L, 7L)
            .timestampMicrosecondsColumn(1L, 1L, 2L, 3L, 3L, 3L, 4L, 4L, 5L, 5L, 6L, 6L, 7L)
            .timestampMillisecondsColumn(1L, 1L, 2L, 3L, 3L, 3L, 4L, 4L, 5L, 5L, 6L, 6L, 7L)
            .timestampNanosecondsColumn(1L, 1L, 2L, 3L, 3L, 3L, 4L, 4L, 5L, 5L, 6L, 6L, 7L)
            .build()) {

      for (int orderIndex = 3; orderIndex < unsorted.getNumberOfColumns(); orderIndex++) {
        try (Table sorted = unsorted.orderBy(OrderByArg.asc(0), OrderByArg.asc(1), OrderByArg.asc(orderIndex));
             ColumnVector expectSortedAggColumn = ColumnVector.fromBoxedInts(7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6, 8)) {
          ColumnVector sortedAggColumn = sorted.getColumn(2);
          assertColumnsAreEqual(expectSortedAggColumn, sortedAggColumn);

          DType type = unsorted.getColumn(orderIndex).getType();
          try (Scalar preceding = getScalar(type, 1L);
               Scalar following = getScalar(type, 1L)) {
            try (WindowOptions window = WindowOptions.builder()
                .minPeriods(1)
                .window(preceding, following)
                .orderByColumnIndex(orderIndex)
                .build()) {
              try (Table windowAggResults = sorted.groupBy(0, 1).aggregateWindowsOverRanges(
                  RollingAggregation.count().onColumn(2).overWindow(window));
                   ColumnVector expect = ColumnVector.fromBoxedInts(3, 3, 4, 2, 4, 4, 4, 4, 4, 4, 5, 5, 3)) {
                assertColumnsAreEqual(expect, windowAggResults.getColumn(0));
              }
            }
          }
        }
      }
    }
  }

  @Test
  void testRangeWindowingLead() {
    try (Table unsorted = new Table.TestBuilder()
        .column(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1) // GBY Key
        .column(0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 2) // GBY Key
        .column(7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6, 8) // Agg Column
        .column(1L, 1L, 2L, 3L, 3L, 3L, 4L, 4L, 5L, 5L, 6L, 6L, 7L) // orderBy Key
        .column((short) 1, (short)1, (short)2, (short)3, (short)3, (short)3, (short)4, (short)4, (short)5, (short)5, (short)6, (short)6, (short)7) // orderBy Key
        .column(1, 1, 2, 3, 3, 3, 4, 4, 5, 5, 6, 6, 7) // orderBy Key
        .column((byte) 1, (byte)1, (byte)2, (byte)3, (byte)3, (byte)3, (byte)4, (byte)4, (byte)5, (byte)5, (byte)6, (byte)6, (byte)7) // orderBy Key
        .timestampDayColumn(1, 1, 2, 3, 3, 3, 4, 4, 5, 5, 6, 6, 7) // Timestamp orderBy Key
        .timestampSecondsColumn(1L, 1L, 2L, 3L, 3L, 3L, 4L, 4L, 5L, 5L, 6L, 6L, 7L)
        .timestampMicrosecondsColumn(1L, 1L, 2L, 3L, 3L, 3L, 4L, 4L, 5L, 5L, 6L, 6L, 7L)
        .timestampMillisecondsColumn(1L, 1L, 2L, 3L, 3L, 3L, 4L, 4L, 5L, 5L, 6L, 6L, 7L)
        .timestampNanosecondsColumn(1L, 1L, 2L, 3L, 3L, 3L, 4L, 4L, 5L, 5L, 6L, 6L, 7L)
        .build()) {

      for (int orderIndex = 3; orderIndex < unsorted.getNumberOfColumns(); orderIndex++) {
        try (Table sorted = unsorted.orderBy(OrderByArg.asc(0), OrderByArg.asc(1), OrderByArg.asc(orderIndex));
             ColumnVector expectSortedAggColumn = ColumnVector.fromBoxedInts(7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6, 8)) {
          ColumnVector sortedAggColumn = sorted.getColumn(2);
          assertColumnsAreEqual(expectSortedAggColumn, sortedAggColumn);

          DType type = unsorted.getColumn(orderIndex).getType();
          try (Scalar preceding = getScalar(type, 1L);
               Scalar following = getScalar(type, 1L)) {
            try (WindowOptions window = WindowOptions.builder()
                .minPeriods(1)
                .window(preceding, following)
                .orderByColumnIndex(orderIndex)
                .build()) {

              try (Table windowAggResults = sorted.groupBy(0, 1)
                  .aggregateWindowsOverRanges(RollingAggregation.lead(1)
                      .onColumn(2)
                      .overWindow(window));
                   ColumnVector expect = ColumnVector.fromBoxedInts(5, 1, 9, null, 9, 8, 2, null, 0, 6, 6, 8, null)) {
                assertColumnsAreEqual(expect, windowAggResults.getColumn(0));
              }
            }
          }
        }
      }
    }
  }

  @Test
  void testRangeWindowingMax() {
    try (Table unsorted = new Table.TestBuilder()
        .column(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1) // GBY Key
        .column(0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 2) // GBY Key
        .column(7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6, 8) // Agg Column
        .column(1L, 1L, 2L, 3L, 3L, 3L, 4L, 4L, 5L, 5L, 6L, 6L, 7L) // orderBy Key
        .column((short) 1, (short)1, (short)2, (short)3, (short)3, (short)3, (short)4, (short)4, (short)5, (short)5, (short)6, (short)6, (short)7) // orderBy Key
        .column(1, 1, 2, 3, 3, 3, 4, 4, 5, 5, 6, 6, 7) // orderBy Key
        .column((byte) 1, (byte)1, (byte)2, (byte)3, (byte)3, (byte)3, (byte)4, (byte)4, (byte)5, (byte)5, (byte)6, (byte)6, (byte)7) // orderBy Key
        .timestampDayColumn(1, 1, 2, 3, 3, 3, 4, 4, 5, 5, 6, 6, 7) // Timestamp orderBy Key
        .timestampSecondsColumn(1L, 1L, 2L, 3L, 3L, 3L, 4L, 4L, 5L, 5L, 6L, 6L, 7L)
        .timestampMicrosecondsColumn(1L, 1L, 2L, 3L, 3L, 3L, 4L, 4L, 5L, 5L, 6L, 6L, 7L)
        .timestampMillisecondsColumn(1L, 1L, 2L, 3L, 3L, 3L, 4L, 4L, 5L, 5L, 6L, 6L, 7L)
        .timestampNanosecondsColumn(1L, 1L, 2L, 3L, 3L, 3L, 4L, 4L, 5L, 5L, 6L, 6L, 7L)
        .build()) {

      for (int orderIndex = 3; orderIndex < unsorted.getNumberOfColumns(); orderIndex++) {
        try (Table sorted = unsorted.orderBy(OrderByArg.asc(0), OrderByArg.asc(1), OrderByArg.asc(orderIndex));
             ColumnVector expectSortedAggColumn = ColumnVector.fromBoxedInts(7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6, 8)) {
          ColumnVector sortedAggColumn = sorted.getColumn(2);
          assertColumnsAreEqual(expectSortedAggColumn, sortedAggColumn);

          DType type = unsorted.getColumn(orderIndex).getType();
          try (Scalar preceding = getScalar(type, 1L);
               Scalar following = getScalar(type, 1L)) {
            try (WindowOptions window = WindowOptions.builder()
                .minPeriods(1)
                .window(preceding, following)
                .orderByColumnIndex(orderIndex)
                .build()) {

              try (Table windowAggResults = sorted.groupBy(0, 1)
                  .aggregateWindowsOverRanges(RollingAggregation.max().onColumn(2).overWindow(window));
                   ColumnVector expect = ColumnVector.fromBoxedInts(7, 7, 9, 9, 9, 9, 9, 9, 8, 8, 8, 8, 8)) {
                assertColumnsAreEqual(expect, windowAggResults.getColumn(0));
              }
            }

            try (Scalar one = Scalar.fromInt(1);
                 Scalar two = Scalar.fromInt(2);
                 WindowOptions window = WindowOptions.builder()
                     .minPeriods(1)
                     .window(two, one)
                     .build()) {

              try (Table windowAggResults = sorted.groupBy(0, 1)
                  .aggregateWindows(RollingAggregation.max().onColumn(2).overWindow(window));
                   ColumnVector expect = ColumnVector.fromBoxedInts(7, 7, 9, 9, 9, 9, 9, 8, 8, 8, 6, 8, 8)) {
                assertColumnsAreEqual(expect, windowAggResults.getColumn(0));
              }
            }
          }
        }
      }
    }
  }

  @Test
  void testRangeWindowingRowNumber() {
    try (Table unsorted = new Table.TestBuilder()
        .column(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1) // GBY Key
        .column(0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 2) // GBY Key
        .column(7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6, 8) // Agg Column
        .column(1L, 1L, 2L, 3L, 3L, 3L, 4L, 4L, 5L, 5L, 6L, 6L, 7L) // orderBy Key
        .column((short) 1, (short)1, (short)2, (short)3, (short)3, (short)3, (short)4, (short)4, (short)5, (short)5, (short)6, (short)6, (short)7) // orderBy Key
        .column(1, 1, 2, 3, 3, 3, 4, 4, 5, 5, 6, 6, 7) // orderBy Key
        .column((byte) 1, (byte)1, (byte)2, (byte)3, (byte)3, (byte)3, (byte)4, (byte)4, (byte)5, (byte)5, (byte)6, (byte)6, (byte)7) // orderBy Key
        .timestampDayColumn(1, 1, 2, 3, 3, 3, 4, 4, 5, 5, 6, 6, 7) // Timestamp orderBy Key
        .timestampSecondsColumn(1L, 1L, 2L, 3L, 3L, 3L, 4L, 4L, 5L, 5L, 6L, 6L, 7L)
        .timestampMicrosecondsColumn(1L, 1L, 2L, 3L, 3L, 3L, 4L, 4L, 5L, 5L, 6L, 6L, 7L)
        .timestampMillisecondsColumn(1L, 1L, 2L, 3L, 3L, 3L, 4L, 4L, 5L, 5L, 6L, 6L, 7L)
        .timestampNanosecondsColumn(1L, 1L, 2L, 3L, 3L, 3L, 4L, 4L, 5L, 5L, 6L, 6L, 7L)
        .build()) {

      for (int orderIndex = 3; orderIndex < unsorted.getNumberOfColumns(); orderIndex++) {
        try (Table sorted = unsorted.orderBy(OrderByArg.asc(0), OrderByArg.asc(1), OrderByArg.asc(orderIndex));
             ColumnVector expectSortedAggColumn = ColumnVector.fromBoxedInts(7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6, 8)) {
          ColumnVector sortedAggColumn = sorted.getColumn(2);
          assertColumnsAreEqual(expectSortedAggColumn, sortedAggColumn);

          DType type = unsorted.getColumn(orderIndex).getType();
          try (Scalar preceding = getScalar(type, 2L);
               Scalar following = getScalar(type, 0L)) {
            try (WindowOptions window = WindowOptions.builder()
                .minPeriods(1)
                .window(preceding, following)
                .orderByColumnIndex(orderIndex)
                .build()) {

              try (Table windowAggResults = sorted.groupBy(0, 1)
                  .aggregateWindowsOverRanges(RollingAggregation.rowNumber().onColumn(2).overWindow(window));
                   ColumnVector expect = ColumnVector.fromBoxedInts(1, 2, 3, 4, 1, 2, 3, 4, 1, 2, 3, 4, 5)) {
                assertColumnsAreEqual(expect, windowAggResults.getColumn(0));
              }
            }
          }
        }
      }
    }
  }

  @Test
  void testRangeWindowingCountDescendingTimestamps() {
    try (Table unsorted = new Table.TestBuilder()
        .column(1, 1, 1, 1,  1, 1, 1, 1,  1, 1, 1, 1, 1) // GBY Key
        .column(0, 0, 0, 0,  1, 1, 1, 1,  2, 2, 2, 2, 2) // GBY Key
        .column(7, 5, 1, 9,  7, 9, 8, 2,  8, 0, 6, 6, 8) // Agg Column
        .column((short)7, (short)6, (short)6, (short)5, (short)5, (short)4, (short)4, (short)3, (short)3, (short)3, (short)2, (short)1, (short)1)
        .column(7L, 6L, 6L, 5L, 5L, 4L, 4L, 3L, 3L, 3L, 2L, 1L, 1L)
        .column(7, 6, 6, 5,  5, 4, 4, 3,  3, 3, 2, 1, 1)
        .column((byte)7, (byte)6, (byte)6, (byte)5,  (byte)5, (byte)4, (byte)4, (byte)3,  (byte)3, (byte)3, (byte)2, (byte)1, (byte)1)
        .timestampDayColumn(7, 6, 6, 5,  5, 4, 4, 3,  3, 3, 2, 1, 1) // Timestamp Key
        .timestampSecondsColumn(7L, 6L, 6L, 5L, 5L, 4L, 4L, 3L, 3L, 3L, 2L, 1L, 1L)
        .timestampMicrosecondsColumn(7L, 6L, 6L, 5L, 5L, 4L, 4L, 3L, 3L, 3L, 2L, 1L, 1L)
        .timestampMillisecondsColumn(7L, 6L, 6L, 5L, 5L, 4L, 4L, 3L, 3L, 3L, 2L, 1L, 1L)
        .timestampNanosecondsColumn(7L, 6L, 6L, 5L, 5L, 4L, 4L, 3L, 3L, 3L, 2L, 1L, 1L)
        .build()) {

      for (int orderIndex = 3; orderIndex < unsorted.getNumberOfColumns(); orderIndex++) {
        try (Table sorted = unsorted.orderBy(OrderByArg.asc(0), OrderByArg.asc(1), OrderByArg.desc(orderIndex));
             ColumnVector expectSortedAggColumn = ColumnVector.fromBoxedInts(7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6, 8)) {
          ColumnVector sortedAggColumn = sorted.getColumn(2);
          assertColumnsAreEqual(expectSortedAggColumn, sortedAggColumn);

          DType type = unsorted.getColumn(orderIndex).getType();
          try (Scalar preceding_0 = getScalar(type, 2L);
               Scalar following_0 = getScalar(type, 1L);
               Scalar preceding_1 = getScalar(type, 3L);
               Scalar following_1 = getScalar(type, 0L)) {

            try (WindowOptions window_0 = WindowOptions.builder()
                  .minPeriods(1)
                  .window(preceding_0, following_0)
                  .orderByColumnIndex(orderIndex)
                  .orderByDescending()
                  .build();

                 WindowOptions window_1 = WindowOptions.builder()
                  .minPeriods(1)
                  .window(preceding_1, following_1)
                  .orderByColumnIndex(orderIndex)
                  .orderByDescending()
                  .build()) {

              try (Table windowAggResults = sorted.groupBy(0, 1)
                  .aggregateWindowsOverRanges(
                      RollingAggregation.count().onColumn(2).overWindow(window_0),
                      RollingAggregation.sum().onColumn(2).overWindow(window_1));
                   ColumnVector expect_0 = ColumnVector.fromBoxedInts(3, 4, 4, 4, 3, 4, 4, 4, 3, 3, 5, 5, 5);
                   ColumnVector expect_1 = ColumnVector.fromBoxedLongs(7L, 13L, 13L, 22L, 7L, 24L, 24L, 26L, 8L, 8L, 14L, 28L, 28L)) {
                assertColumnsAreEqual(expect_0, windowAggResults.getColumn(0));
                assertColumnsAreEqual(expect_1, windowAggResults.getColumn(1));
              }
            }
          }
        }
      }
    }
  }

  @Test
  void testRangeWindowingWithoutGroupByColumns() {
    try (Table unsorted = new Table.TestBuilder()
        .column(             7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6, 8) // Agg Column
        .column(1L, 1L, 2L, 3L, 3L, 3L, 4L, 4L, 5L, 5L, 6L, 6L, 7L) // orderBy Key
        .column((short) 1, (short)1, (short)2, (short)3, (short)3, (short)3, (short)4, (short)4, (short)5, (short)5, (short)6, (short)6, (short)7) // orderBy Key
        .column(1, 1, 2, 3, 3, 3, 4, 4, 5, 5, 6, 6, 7) // orderBy Key
        .column((byte) 1, (byte)1, (byte)2, (byte)3, (byte)3, (byte)3, (byte)4, (byte)4, (byte)5, (byte)5, (byte)6, (byte)6, (byte)7) // orderBy Key
        .timestampDayColumn(1, 1, 2, 3, 3, 3, 4, 4, 5, 5, 6, 6, 7) // Timestamp orderBy Key
        .timestampSecondsColumn(1L, 1L, 2L, 3L, 3L, 3L, 4L, 4L, 5L, 5L, 6L, 6L, 7L)
        .timestampMicrosecondsColumn(1L, 1L, 2L, 3L, 3L, 3L, 4L, 4L, 5L, 5L, 6L, 6L, 7L)
        .timestampMillisecondsColumn(1L, 1L, 2L, 3L, 3L, 3L, 4L, 4L, 5L, 5L, 6L, 6L, 7L)
        .timestampNanosecondsColumn(1L, 1L, 2L, 3L, 3L, 3L, 4L, 4L, 5L, 5L, 6L, 6L, 7L)
        .build()) {

      for (int orderIndex = 3; orderIndex < unsorted.getNumberOfColumns(); orderIndex++) {
        try (Table sorted = unsorted.orderBy(OrderByArg.asc(orderIndex));
             ColumnVector expectSortedAggColumn = ColumnVector.fromBoxedInts(7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6, 8)) {
          ColumnVector sortedAggColumn = sorted.getColumn(0);
          assertColumnsAreEqual(expectSortedAggColumn, sortedAggColumn);

          DType type = unsorted.getColumn(orderIndex).getType();
          try (Scalar preceding = getScalar(type, 1L);
               Scalar following = getScalar(type, 1L)) {
            try (WindowOptions window = WindowOptions.builder()
                .minPeriods(1)
                .window(preceding, following)
                .orderByColumnIndex(orderIndex)
                .build();) {

              try (Table windowAggResults = sorted.groupBy()
                  .aggregateWindowsOverRanges(RollingAggregation.count().onColumn(1).overWindow(window));
                   ColumnVector expect = ColumnVector.fromBoxedInts(3, 3, 6, 6, 6, 6, 7, 7, 6, 6, 5, 5, 3)) {
                assertColumnsAreEqual(expect, windowAggResults.getColumn(0));
              }
            }
          }
        }
      }
    }
  }

  @Test
  void testRangeWindowingOrderByUnsupportedDataTypeExceptions() {
    try (Table table = new Table.TestBuilder()
        .column(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1) // GBY Key
        .column(0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 2) // GBY Key
        .column(7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6, 8) // Agg Column
        .column(true, false, true, false, true, false, true, false, false, false, false, false, false) // orderBy Key
        .build()) {

      try (Scalar one = Scalar.fromInt(1);
           WindowOptions rangeBasedWindow = WindowOptions.builder()
               .minPeriods(1)
               .window(one, one)
               .orderByColumnIndex(3)
               .build()) {

        assertThrows(IllegalArgumentException.class,
            () -> table
                .groupBy(0, 1)
                .aggregateWindowsOverRanges(RollingAggregation.max().onColumn(2).overWindow(rangeBasedWindow)));
      }
    }
  }

  @Test
  void testInvalidWindowTypeExceptions() {
    try (Scalar one = Scalar.fromInt(1);
         Table table = new Table.TestBuilder()
             .column(             1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1) // GBY Key
             .column(             0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 2) // GBY Key
             .timestampDayColumn( 1, 1, 2, 3, 3, 3, 4, 4, 5, 5, 6, 6, 7) // Timestamp Key
             .column(             7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6, 8) // Agg Column
             .build()) {


      try (WindowOptions rowBasedWindow = WindowOptions.builder()
          .minPeriods(1)
          .window(one, one)
          .build()) {
        assertThrows(IllegalArgumentException.class, () -> table.groupBy(0, 1).aggregateWindowsOverRanges(RollingAggregation.max().onColumn(3).overWindow(rowBasedWindow)));
      }

      try (WindowOptions rangeBasedWindow = WindowOptions.builder()
          .minPeriods(1)
          .window(one, one)
          .orderByColumnIndex(2)
          .build()) {
        assertThrows(IllegalArgumentException.class, () -> table.groupBy(0, 1).aggregateWindows(RollingAggregation.max().onColumn(3).overWindow(rangeBasedWindow)));
      }
    }
  }

  @Test
  void testRangeWindowingCountUnboundedPreceding() {
    try (Table unsorted = new Table.TestBuilder()
        .column(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1) // GBY Key
        .column(0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 2) // GBY Key
        .column(7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6, 8) // Agg Column
        .column(1L, 1L, 2L, 3L, 3L, 3L, 4L, 4L, 5L, 5L, 6L, 6L, 7L) // orderBy Key
        .column((short) 1, (short)1, (short)2, (short)3, (short)3, (short)3, (short)4, (short)4, (short)5, (short)5, (short)6, (short)6, (short)7) // orderBy Key
        .column(1, 1, 2, 3, 3, 3, 4, 4, 5, 5, 6, 6, 7) // orderBy Key
        .column((byte) 1, (byte)1, (byte)2, (byte)3, (byte)3, (byte)3, (byte)4, (byte)4, (byte)5, (byte)5, (byte)6, (byte)6, (byte)7) // orderBy Key
        .timestampDayColumn(1, 1, 2, 3, 3, 3, 4, 4, 5, 5, 6, 6, 7) // Timestamp orderBy Key
        .timestampSecondsColumn(1L, 1L, 2L, 3L, 3L, 3L, 4L, 4L, 5L, 5L, 6L, 6L, 7L)
        .timestampMicrosecondsColumn(1L, 1L, 2L, 3L, 3L, 3L, 4L, 4L, 5L, 5L, 6L, 6L, 7L)
        .timestampMillisecondsColumn(1L, 1L, 2L, 3L, 3L, 3L, 4L, 4L, 5L, 5L, 6L, 6L, 7L)
        .timestampNanosecondsColumn(1L, 1L, 2L, 3L, 3L, 3L, 4L, 4L, 5L, 5L, 6L, 6L, 7L)
        .build()) {

      for (int orderIndex = 3; orderIndex < unsorted.getNumberOfColumns(); orderIndex++) {
        try (Table sorted = unsorted.orderBy(OrderByArg.asc(0), OrderByArg.asc(1), OrderByArg.asc(orderIndex));
             ColumnVector expectSortedAggColumn = ColumnVector.fromBoxedInts(7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6, 8)) {
          ColumnVector sortedAggColumn = sorted.getColumn(2);
          assertColumnsAreEqual(expectSortedAggColumn, sortedAggColumn);

          DType type = unsorted.getColumn(orderIndex).getType();
          try (Scalar following = getScalar(type, 1L)) {
            try (WindowOptions window = WindowOptions.builder()
                .minPeriods(1)
                .unboundedPreceding()
                .following(following)
                .orderByColumnIndex(orderIndex)
                .build();) {

              try (Table windowAggResults = sorted.groupBy(0, 1)
                  .aggregateWindowsOverRanges(RollingAggregation.count().onColumn(2).overWindow(window));
                   ColumnVector expect = ColumnVector.fromBoxedInts(3, 3, 4, 4, 4, 4, 4, 4, 4, 4, 5, 5, 5)) {
                assertColumnsAreEqual(expect, windowAggResults.getColumn(0));
              }
            }
          }
        }
      }
    }
  }

  @Test
  void testRangeWindowingCountUnboundedASCWithNullsFirst() {
    try (Table unsorted = new Table.TestBuilder()
        .column(1, 1, 1, 1, 1, 1,  1, 1, 1, 1, 1, 1, 1) // GBY Key
        .column(0, 0, 0, 0, 0, 0,  1, 1, 1, 1, 1, 1, 1) // GBY Key
        .column(7, 5, 1, 9, 7, 9,  8, 2, 8, 0, 6, 6, 8) // Agg Column
        .column( null, null, null, 2, 3, 5, null, null, 1, 2, 4, 5, 7) // Timestamp Key
        .column( null, null, null, 2L, 3L, 5L, null, null, 1L, 2L, 4L, 5L, 7L) // orderBy Key
        .column( null, null, null, (short)2, (short)3, (short)5, null, null, (short)1, (short)2, (short)4, (short)5, (short)7) // orderBy Key
        .column( null, null, null, (byte)2, (byte)3, (byte)5, null, null, (byte)1, (byte)2, (byte)4, (byte)5, (byte)7) // orderBy Key
        .timestampDayColumn( null, null, null, 2, 3, 5, null, null, 1, 2, 4, 5, 7) // Timestamp orderBy Key
        .timestampSecondsColumn( null, null, null, 2L, 3L, 5L, null, null, 1L, 2L, 4L, 5L, 7L)
        .timestampMicrosecondsColumn( null, null, null, 2L, 3L, 5L, null, null, 1L, 2L, 4L, 5L, 7L)
        .timestampMillisecondsColumn( null, null, null, 2L, 3L, 5L, null, null, 1L, 2L, 4L, 5L, 7L)
        .timestampNanosecondsColumn( null, null, null, 2L, 3L, 5L, null, null, 1L, 2L, 4L, 5L, 7L)
        .build()) {

      for (int orderIndex = 3; orderIndex < unsorted.getNumberOfColumns(); orderIndex++) {
        try (Table sorted = unsorted.orderBy(OrderByArg.asc(0), OrderByArg.asc(1), OrderByArg.asc(orderIndex, true));
             ColumnVector expectSortedAggColumn = ColumnVector.fromBoxedInts(7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6, 8)) {
          ColumnVector sortedAggColumn = sorted.getColumn(2);
          assertColumnsAreEqual(expectSortedAggColumn, sortedAggColumn);

          DType type = unsorted.getColumn(orderIndex).getType();
          try (Scalar following1 = getScalar(type, 1L);
               Scalar preceding1 = getScalar(type, 1L);
               Scalar following0 = getScalar(type, 0L);
               Scalar preceding0 = getScalar(type, 0L);) {
            try (WindowOptions unboundedPrecedingOneFollowing = WindowOptions.builder()
                .minPeriods(1)
                .unboundedPreceding()
                .following(following1)
                .orderByColumnIndex(orderIndex)
                .build();

            WindowOptions onePrecedingUnboundedFollowing = WindowOptions.builder()
                .minPeriods(1)
                .preceding(preceding1)
                .unboundedFollowing()
                .orderByColumnIndex(orderIndex)
                .build();

            WindowOptions unboundedPrecedingAndFollowing = WindowOptions.builder()
                .minPeriods(1)
                .unboundedPreceding()
                .unboundedFollowing()
                .orderByColumnIndex(orderIndex)
                .build();

            WindowOptions unboundedPrecedingAndCurrentRow = WindowOptions.builder()
                .minPeriods(1)
                .unboundedPreceding()
                .following(following0)
                .orderByColumnIndex(orderIndex)
                .build();

            WindowOptions currentRowAndUnboundedFollowing = WindowOptions.builder()
                .minPeriods(1)
                .preceding(preceding0)
                .unboundedFollowing()
                .orderByColumnIndex(orderIndex)
                .build();) {

              try (Table windowAggResults = sorted.groupBy(0, 1)
                  .aggregateWindowsOverRanges(
                      RollingAggregation.count().onColumn(2).overWindow(unboundedPrecedingOneFollowing),
                      RollingAggregation.count().onColumn(2).overWindow(onePrecedingUnboundedFollowing),
                      RollingAggregation.count().onColumn(2).overWindow(unboundedPrecedingAndFollowing),
                      RollingAggregation.count().onColumn(2).overWindow(unboundedPrecedingAndCurrentRow),
                      RollingAggregation.count().onColumn(2).overWindow(currentRowAndUnboundedFollowing));
                   ColumnVector expect_0 = ColumnVector.fromBoxedInts(3, 3, 3, 5, 5, 6, 2, 2, 4, 4, 6, 6, 7);
                   ColumnVector expect_1 = ColumnVector.fromBoxedInts(6, 6, 6, 3, 3, 1, 7, 7, 5, 5, 3, 3, 1);
                   ColumnVector expect_2 = ColumnVector.fromBoxedInts(6, 6, 6, 6, 6, 6, 7, 7, 7, 7, 7, 7, 7);
                   ColumnVector expect_3 = ColumnVector.fromBoxedInts(3, 3, 3, 4, 5, 6, 2, 2, 3, 4, 5, 6, 7);
                   ColumnVector expect_4 = ColumnVector.fromBoxedInts(6, 6, 6, 3, 2, 1, 7, 7, 5, 4, 3, 2, 1)) {

                assertColumnsAreEqual(expect_0, windowAggResults.getColumn(0));
                assertColumnsAreEqual(expect_1, windowAggResults.getColumn(1));
                assertColumnsAreEqual(expect_2, windowAggResults.getColumn(2));
                assertColumnsAreEqual(expect_3, windowAggResults.getColumn(3));
                assertColumnsAreEqual(expect_4, windowAggResults.getColumn(4));
              }
            }
          }
        }
      }
    }
  }

  @Test
  void testRangeWindowingCountUnboundedDESCWithNullsFirst() {
    try (Table unsorted = new Table.TestBuilder()
        .column(1, 1, 1, 1, 1, 1,  1, 1, 1, 1, 1, 1, 1) // GBY Key
        .column(0, 0, 0, 0, 0, 0,  1, 1, 1, 1, 1, 1, 1) // GBY Key
        .column(7, 5, 1, 9, 7, 9,  8, 2, 8, 0, 6, 6, 8) // Agg Column
        .column(null, null, null, 5, 3, 2,  null, null, 7, 5, 4, 2, 1) // Timestamp Key
        .column(null, null, null, 5L, 3L, 2L,  null, null, 7L, 5L, 4L, 2L, 1L) // orderby Key
        .column(null, null, null, (short)5, (short)3, (short)2,  null, null, (short)7, (short)5, (short)4, (short)2, (short)1) // orderby Key
        .column(null, null, null, (byte)5, (byte)3, (byte)2,  null, null, (byte)7, (byte)5, (byte)4, (byte)2, (byte)1) // orderby Key
        .timestampDayColumn(null, null, null, 5, 3, 2, null, null, 7, 5, 4, 2, 1) // Timestamp orderby Key
        .timestampSecondsColumn( null, null, null, 5L, 3L, 2L,  null, null, 7L, 5L, 4L, 2L, 1L)
        .timestampMicrosecondsColumn( null, null, null, 5L, 3L, 2L,  null, null, 7L, 5L, 4L, 2L, 1L)
        .timestampMillisecondsColumn( null, null, null, 5L, 3L, 2L,  null, null, 7L, 5L, 4L, 2L, 1L)
        .timestampNanosecondsColumn( null, null, null, 5L, 3L, 2L,  null, null, 7L, 5L, 4L, 2L, 1L)
        .build()) {

      for (int orderIndex = 3; orderIndex < unsorted.getNumberOfColumns(); orderIndex++) {
        try (Table sorted = unsorted.orderBy(OrderByArg.asc(0), OrderByArg.asc(1), OrderByArg.desc(orderIndex, false));
             ColumnVector expectSortedAggColumn = ColumnVector.fromBoxedInts(7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6, 8)) {
          ColumnVector sortedAggColumn = sorted.getColumn(2);
          assertColumnsAreEqual(expectSortedAggColumn, sortedAggColumn);

          DType type = unsorted.getColumn(orderIndex).getType();
          try (Scalar following1 = getScalar(type, 1L);
               Scalar preceding1 = getScalar(type, 1L);
               Scalar following0 = getScalar(type, 0L);
               Scalar preceding0 = getScalar(type, 0L);) {

            try (WindowOptions unboundedPrecedingOneFollowing = WindowOptions.builder()
                .minPeriods(1)
                .unboundedPreceding()
                .following(following1)
                .orderByColumnIndex(orderIndex)
                .orderByDescending()
                .build();

            WindowOptions onePrecedingUnboundedFollowing = WindowOptions.builder()
                .minPeriods(1)
                .preceding(preceding1)
                .unboundedFollowing()
                .orderByColumnIndex(orderIndex)
                .orderByDescending()
                .build();

            WindowOptions unboundedPrecedingAndFollowing = WindowOptions.builder()
                .minPeriods(1)
                .unboundedPreceding()
                .unboundedFollowing()
                .orderByColumnIndex(orderIndex)
                .orderByDescending()
                .build();

            WindowOptions unboundedPrecedingAndCurrentRow = WindowOptions.builder()
                .minPeriods(1)
                .unboundedPreceding()
                .following(following0)
                .orderByColumnIndex(orderIndex)
                .orderByDescending()
                .build();

            WindowOptions currentRowAndUnboundedFollowing = WindowOptions.builder()
                .minPeriods(1)
                .preceding(preceding0)
                .unboundedFollowing()
                .orderByColumnIndex(orderIndex)
                .orderByDescending()
                .build();) {

              try (Table windowAggResults = sorted.groupBy(0, 1)
                  .aggregateWindowsOverRanges(
                      RollingAggregation.count().onColumn(2).overWindow(unboundedPrecedingOneFollowing),
                      RollingAggregation.count().onColumn(2).overWindow(onePrecedingUnboundedFollowing),
                      RollingAggregation.count().onColumn(2).overWindow(unboundedPrecedingAndFollowing),
                      RollingAggregation.count().onColumn(2).overWindow(unboundedPrecedingAndCurrentRow),
                      RollingAggregation.count().onColumn(2).overWindow(currentRowAndUnboundedFollowing));
                   ColumnVector expect_0 = ColumnVector.fromBoxedInts(3, 3, 3, 4, 6, 6, 2, 2, 3, 5, 5, 7, 7);
                   ColumnVector expect_1 = ColumnVector.fromBoxedInts(6, 6, 6, 3, 2, 2, 7, 7, 5, 4, 4, 2, 2);
                   ColumnVector expect_2 = ColumnVector.fromBoxedInts(6, 6, 6, 6, 6, 6, 7, 7, 7, 7, 7, 7, 7);
                   ColumnVector expect_3 = ColumnVector.fromBoxedInts(3, 3, 3, 4, 5, 6, 2, 2, 3, 4, 5, 6, 7);
                   ColumnVector expect_4 = ColumnVector.fromBoxedInts(6, 6, 6, 3, 2, 1, 7, 7, 5, 4, 3, 2, 1)) {

                assertColumnsAreEqual(expect_0, windowAggResults.getColumn(0));
                assertColumnsAreEqual(expect_1, windowAggResults.getColumn(1));
                assertColumnsAreEqual(expect_2, windowAggResults.getColumn(2));
                assertColumnsAreEqual(expect_3, windowAggResults.getColumn(3));
                assertColumnsAreEqual(expect_4, windowAggResults.getColumn(4));
              }
            }
          }
        }
      }
    }
  }

  @Test
  void testRangeWindowingCountUnboundedASCWithNullsLast() {
    try (Table unsorted = new Table.TestBuilder()
        .column(1, 1, 1, 1, 1, 1,  1, 1, 1, 1, 1, 1, 1) // GBY Key
        .column(0, 0, 0, 0, 0, 0,  1, 1, 1, 1, 1, 1, 1) // GBY Key
        .column(7, 5, 1, 9, 7, 9,  8, 2, 8, 0, 6, 6, 8) // Agg Column
        .column(2, 3, 5, null, null, null,  1, 2, 4, 5, 7, null, null) // Timestamp Key
        .column(2L, 3L, 5L, null, null, null, 1L, 2L, 4L, 5L, 7L, null, null) // order by Key
        .column((short)2, (short)3, (short)5, null, null, null, (short)1, (short)2, (short)4, (short)5, (short)7, null, null) // order by Key
        .column((byte)2, (byte)3, (byte)5, null, null, null, (byte)1, (byte)2, (byte)4, (byte)5, (byte)7, null, null) // order by Key
        .timestampDayColumn( 2, 3, 5, null, null, null,  1, 2, 4, 5, 7, null, null) // Timestamp order by Key
        .timestampSecondsColumn( 2L, 3L, 5L, null, null, null, 1L, 2L, 4L, 5L, 7L, null, null)
        .timestampMicrosecondsColumn( 2L, 3L, 5L, null, null, null, 1L, 2L, 4L, 5L, 7L, null, null)
        .timestampMillisecondsColumn( 2L, 3L, 5L, null, null, null, 1L, 2L, 4L, 5L, 7L, null, null)
        .timestampNanosecondsColumn( 2L, 3L, 5L, null, null, null, 1L, 2L, 4L, 5L, 7L, null, null)
        .build()) {
      for (int orderIndex = 3; orderIndex < unsorted.getNumberOfColumns(); orderIndex++) {
        try (Table sorted = unsorted.orderBy(OrderByArg.asc(0), OrderByArg.asc(1), OrderByArg.asc(orderIndex, false));
             ColumnVector expectSortedAggColumn = ColumnVector.fromBoxedInts(7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6, 8)) {
          ColumnVector sortedAggColumn = sorted.getColumn(2);
          assertColumnsAreEqual(expectSortedAggColumn, sortedAggColumn);

          DType type = unsorted.getColumn(orderIndex).getType();
          try (Scalar following1 = getScalar(type, 1L);
               Scalar preceding1 = getScalar(type, 1L);
               Scalar following0 = getScalar(type, 0L);
               Scalar preceding0 = getScalar(type, 0L);) {
            try (WindowOptions unboundedPrecedingOneFollowing = WindowOptions.builder()
                .minPeriods(1)
                .unboundedPreceding()
                .following(following1)
                .orderByColumnIndex(orderIndex)
                .build();

            WindowOptions onePrecedingUnboundedFollowing = WindowOptions.builder()
                .minPeriods(1)
                .preceding(preceding1)
                .unboundedFollowing()
                .orderByColumnIndex(orderIndex)
                .build();

            WindowOptions unboundedPrecedingAndFollowing = WindowOptions.builder()
                .minPeriods(1)
                .unboundedPreceding()
                .unboundedFollowing()
                .orderByColumnIndex(orderIndex)
                .build();

            WindowOptions unboundedPrecedingAndCurrentRow = WindowOptions.builder()
                .minPeriods(1)
                .unboundedPreceding()
                .following(following0)
                .orderByColumnIndex(orderIndex)
                .build();

            WindowOptions currentRowAndUnboundedFollowing = WindowOptions.builder()
                .minPeriods(1)
                .preceding(preceding0)
                .unboundedFollowing()
                .orderByColumnIndex(orderIndex)
                .build();) {

              try (Table windowAggResults = sorted.groupBy(0, 1)
                  .aggregateWindowsOverRanges(
                      RollingAggregation.count().onColumn(2).overWindow(unboundedPrecedingOneFollowing),
                      RollingAggregation.count().onColumn(2).overWindow(onePrecedingUnboundedFollowing),
                      RollingAggregation.count().onColumn(2).overWindow(unboundedPrecedingAndFollowing),
                      RollingAggregation.count().onColumn(2).overWindow(unboundedPrecedingAndCurrentRow),
                      RollingAggregation.count().onColumn(2).overWindow(currentRowAndUnboundedFollowing));
                   ColumnVector expect_0 = ColumnVector.fromBoxedInts(2, 2, 3, 6, 6, 6, 2, 2, 4, 4, 5, 7, 7);
                   ColumnVector expect_1 = ColumnVector.fromBoxedInts(6, 6, 4, 3, 3, 3, 7, 7, 5, 5, 3, 2, 2);
                   ColumnVector expect_2 = ColumnVector.fromBoxedInts(6, 6, 6, 6, 6, 6, 7, 7, 7, 7, 7, 7, 7);
                   ColumnVector expect_3 = ColumnVector.fromBoxedInts(1, 2, 3, 6, 6, 6, 1, 2, 3, 4, 5, 7, 7);
                   ColumnVector expect_4 = ColumnVector.fromBoxedInts(6, 5, 4, 3, 3, 3, 7, 6, 5, 4, 3, 2, 2)) {

                assertColumnsAreEqual(expect_0, windowAggResults.getColumn(0));
                assertColumnsAreEqual(expect_1, windowAggResults.getColumn(1));
                assertColumnsAreEqual(expect_2, windowAggResults.getColumn(2));
                assertColumnsAreEqual(expect_3, windowAggResults.getColumn(3));
                assertColumnsAreEqual(expect_4, windowAggResults.getColumn(4));
              }
            }
          }
        }
      }
    }
  }

  @Test
  void testRangeWindowingCountUnboundedDESCWithNullsLast() {
    Integer X = null;
    try (Table unsorted = new Table.TestBuilder()
        .column(1, 1, 1, 1, 1, 1,  1, 1, 1, 1, 1, 1, 1) // GBY Key
        .column(0, 0, 0, 0, 0, 0,  1, 1, 1, 1, 1, 1, 1) // GBY Key
        .column(7, 5, 1, 9, 7, 9,  8, 2, 8, 0, 6, 6, 8) // Agg Column
        .column( 5, 3, 2, null, null, null, 7, 5, 4, 2, 1, null, null) // Timestamp Key
        .column(5L, 3L, 2L, null, null, null, 7L, 5L, 4L, 2L, 1L, null, null) // Timestamp Key
        .column((short)5, (short)3, (short)2, null, null, null, (short)7, (short)5, (short)4, (short)2, (short)1, null, null) // Timestamp Key
        .column((byte)5, (byte)3, (byte)2, null, null, null, (byte)7, (byte)5, (byte)4, (byte)2, (byte)1, null, null) // Timestamp Key
        .timestampDayColumn( 5, 3, 2, X, X, X,  7, 5, 4, 2, 1, X, X) // Timestamp Key
        .timestampSecondsColumn( 5L, 3L, 2L, null, null, null, 7L, 5L, 4L, 2L, 1L, null, null)
        .timestampMicrosecondsColumn( 5L, 3L, 2L, null, null, null, 7L, 5L, 4L, 2L, 1L, null, null)
        .timestampMillisecondsColumn( 5L, 3L, 2L, null, null, null, 7L, 5L, 4L, 2L, 1L, null, null)
        .timestampNanosecondsColumn( 5L, 3L, 2L, null, null, null, 7L, 5L, 4L, 2L, 1L, null, null)
        .build()) {
      for (int orderIndex = 3; orderIndex < unsorted.getNumberOfColumns(); orderIndex++) {
        try (Table sorted = unsorted.orderBy(OrderByArg.asc(0), OrderByArg.asc(1), OrderByArg.desc(orderIndex, true));
             ColumnVector expectSortedAggColumn = ColumnVector.fromBoxedInts(7, 5, 1, 9, 7, 9, 8, 2, 8, 0, 6, 6, 8)) {
          ColumnVector sortedAggColumn = sorted.getColumn(2);
          assertColumnsAreEqual(expectSortedAggColumn, sortedAggColumn);

          DType type = unsorted.getColumn(orderIndex).getType();
          try (Scalar following1 = getScalar(type, 1L);
               Scalar preceding1 = getScalar(type, 1L);
               Scalar following0 = getScalar(type, 0L);
               Scalar preceding0 = getScalar(type, 0L);) {
            try (WindowOptions unboundedPrecedingOneFollowing = WindowOptions.builder()
                .minPeriods(1)
                .unboundedPreceding()
                .following(following1)
                .orderByColumnIndex(orderIndex)
                .orderByDescending()
                .build();

            WindowOptions onePrecedingUnboundedFollowing = WindowOptions.builder()
                .minPeriods(1)
                .preceding(preceding1)
                .unboundedFollowing()
                .orderByColumnIndex(orderIndex)
                .orderByDescending()
                .build();

            WindowOptions unboundedPrecedingAndFollowing = WindowOptions.builder()
                .minPeriods(1)
                .unboundedPreceding()
                .unboundedFollowing()
                .orderByColumnIndex(orderIndex)
                .orderByDescending()
                .build();

            WindowOptions unboundedPrecedingAndCurrentRow = WindowOptions.builder()
                .minPeriods(1)
                .unboundedPreceding()
                .following(following0)
                .orderByColumnIndex(orderIndex)
                .orderByDescending()
                .build();

            WindowOptions currentRowAndUnboundedFollowing = WindowOptions.builder()
                .minPeriods(1)
                .preceding(preceding0)
                .unboundedFollowing()
                .orderByColumnIndex(orderIndex)
                .orderByDescending()
                .build();) {

              try (Table windowAggResults = sorted.groupBy(0, 1)
                  .aggregateWindowsOverRanges(
                      RollingAggregation.count().onColumn(2).overWindow(unboundedPrecedingOneFollowing),
                      RollingAggregation.count().onColumn(2).overWindow(onePrecedingUnboundedFollowing),
                      RollingAggregation.count().onColumn(2).overWindow(unboundedPrecedingAndFollowing),
                      RollingAggregation.count().onColumn(2).overWindow(unboundedPrecedingAndCurrentRow),
                      RollingAggregation.count().onColumn(2).overWindow(currentRowAndUnboundedFollowing));
                   ColumnVector expect_0 = ColumnVector.fromBoxedInts(1, 3, 3, 6, 6, 6, 1, 3, 3, 5, 5, 7, 7);
                   ColumnVector expect_1 = ColumnVector.fromBoxedInts(6, 5, 5, 3, 3, 3, 7, 6, 6, 4, 4, 2, 2);
                   ColumnVector expect_2 = ColumnVector.fromBoxedInts(6, 6, 6, 6, 6, 6, 7, 7, 7, 7, 7, 7, 7);
                   ColumnVector expect_3 = ColumnVector.fromBoxedInts(1, 2, 3, 6, 6, 6, 1, 2, 3, 4, 5, 7, 7);
                   ColumnVector expect_4 = ColumnVector.fromBoxedInts(6, 5, 4, 3, 3, 3, 7, 6, 5, 4, 3, 2, 2)) {

                assertColumnsAreEqual(expect_0, windowAggResults.getColumn(0));
                assertColumnsAreEqual(expect_1, windowAggResults.getColumn(1));
                assertColumnsAreEqual(expect_2, windowAggResults.getColumn(2));
                assertColumnsAreEqual(expect_3, windowAggResults.getColumn(3));
                assertColumnsAreEqual(expect_4, windowAggResults.getColumn(4));
              }
            }
          }
        }
      }
    }
  }

  @Test
  void testGroupByCountWithNulls() {
    try (Table t1 = new Table.TestBuilder().column(null, null,    1,    1,    1,    1)
                                           .column(   1,    1,    1,    1,    1,    1)
                                           .column(   1,    1, null, null,    1,    1)
                                           .column(   1,    1,    1, null,    1,    1)
                                           .build()) {
      try (Table tmp = t1.groupBy(0).aggregate(
          GroupByAggregation.count().onColumn(1),
          GroupByAggregation.count().onColumn(2),
          GroupByAggregation.count().onColumn(3));
           Table t3 = tmp.orderBy(OrderByArg.asc(0, true));
           HostColumnVector groupCol = t3.getColumn(0).copyToHost();
           HostColumnVector countCol = t3.getColumn(1).copyToHost();
           HostColumnVector nullCountCol = t3.getColumn(2).copyToHost();
           HostColumnVector nullCountCol2 = t3.getColumn(3).copyToHost()) {
        // verify t3
        assertEquals(2, t3.getRowCount());

        // compare the grouping columns
        assertTrue(groupCol.isNull(0));
        assertEquals(groupCol.getInt(1), 1);

        // compare the agg columns
        // count(1)
        assertEquals(countCol.getInt(0), 2);
        assertEquals(countCol.getInt(1), 4);

        // count(2)
        assertEquals(nullCountCol.getInt(0), 2);
        assertEquals(nullCountCol.getInt(1), 2); // counts only the non-nulls

        // count(3)
        assertEquals(nullCountCol2.getInt(0), 2);
        assertEquals(nullCountCol2.getInt(1), 3); // counts only the non-nulls
      }
    }
  }

  @Test
  void testGroupByCountWithNullsIncluded() {
    try (Table t1 = new Table.TestBuilder()
            .column(null, null,    1,    1,    1,    1)
            .column(   1,    1,    1,    1,    1,    1)
            .column(   1,    1, null, null,    1,    1)
            .column(   1,    1,    1, null,    1,    1)
            .build()) {
      try (Table tmp = t1.groupBy(0).aggregate(
          GroupByAggregation.count(NullPolicy.INCLUDE).onColumn(1),
          GroupByAggregation.count(NullPolicy.INCLUDE).onColumn(2),
          GroupByAggregation.count(NullPolicy.INCLUDE).onColumn(3),
          GroupByAggregation.count().onColumn(3));
           Table t3 = tmp.orderBy(OrderByArg.asc(0, true));
           HostColumnVector groupCol = t3.getColumn(0).copyToHost();
           HostColumnVector countCol = t3.getColumn(1).copyToHost();
           HostColumnVector nullCountCol = t3.getColumn(2).copyToHost();
           HostColumnVector nullCountCol2 = t3.getColumn(3).copyToHost();
           HostColumnVector nullCountCol3 = t3.getColumn(4).copyToHost()) {
        // verify t3
        assertEquals(2, t3.getRowCount());

        // compare the grouping columns
        assertTrue(groupCol.isNull(0));
        assertEquals(groupCol.getInt(1), 1);

        // compare the agg columns
        // count(1, true)
        assertEquals(countCol.getInt(0), 2);
        assertEquals(countCol.getInt(1), 4);

        // count(2, true)
        assertEquals(nullCountCol.getInt(0), 2);
        assertEquals(nullCountCol.getInt(1), 4); // counts including nulls

        // count(3, true)
        assertEquals(nullCountCol2.getInt(0), 2);
        assertEquals(nullCountCol2.getInt(1), 4); // counts including nulls

        // count(3)
        assertEquals(nullCountCol3.getInt(0), 2);
        assertEquals(nullCountCol3.getInt(1), 3); // counts only the non-nulls
      }
    }
  }

  @Test
  void testGroupByCountWithCollapsingNulls() {
    try (Table t1 = new Table.TestBuilder()
        .column(null, null,    1,    1,    1,    1)
        .column(   1,    1,    1,    1,    1,    1)
        .column(   1,    1, null, null,    1,    1)
        .column(   1,    1,    1, null,    1,    1)
        .build()) {

      GroupByOptions options = GroupByOptions.builder()
          .withIgnoreNullKeys(true)
          .build();

      try (Table tmp = t1.groupBy(options, 0).aggregate(
          GroupByAggregation.count().onColumn(1),
          GroupByAggregation.count().onColumn(2),
          GroupByAggregation.count().onColumn(3));
           Table t3 = tmp.orderBy(OrderByArg.asc(0, true));
           HostColumnVector groupCol = t3.getColumn(0).copyToHost();
           HostColumnVector countCol = t3.getColumn(1).copyToHost();
           HostColumnVector nullCountCol = t3.getColumn(2).copyToHost();
           HostColumnVector nullCountCol2 = t3.getColumn(3).copyToHost()) {
        // (null, 1) => became (1) because we are ignoring nulls
        assertEquals(1, t3.getRowCount());

        // compare the grouping columns
        assertEquals(groupCol.getInt(0), 1);

        // compare the agg columns
        // count(1)
        assertEquals(countCol.getInt(0), 4);

        // count(2)
        assertEquals(nullCountCol.getInt(0), 2); // counts only the non-nulls

        // count(3)
        assertEquals(nullCountCol2.getInt(0), 3); // counts only the non-nulls
      }
    }
  }

  @Test
  void testGroupByMax() {
    try (Table t1 = new Table.TestBuilder().column(   1,    1,    1,    1,    1,    1)
                                           .column(   1,    3,    3,    5,    5,    0)
                                           .column(12.0, 14.0, 13.0, 17.0, 17.0, 17.0)
                                           .build()) {
      try (Table t3 = t1.groupBy(0, 1).aggregate(GroupByAggregation.max().onColumn(2));
           HostColumnVector aggOut1 = t3.getColumn(2).copyToHost()) {
        // verify t3
        assertEquals(4, t3.getRowCount());
        Map<Double, Integer> expectedAggregateResult = new HashMap() {
          {
            // value, count
            put(12.0, 1);
            put(14.0, 1);
            put(17.0, 2);
          }
        };
        for (int i = 0; i < 4; ++i) {
          Double key = aggOut1.getDouble(i);
          assertTrue(expectedAggregateResult.containsKey(key));
          Integer count = expectedAggregateResult.get(key);
          if (count == 1) {
            expectedAggregateResult.remove(key);
          } else {
            expectedAggregateResult.put(key, count - 1);
          }
        }
      }
    }
  }

  @Test
  void testGroupByArgMax() {
    // ArgMax is a sort based aggregation.
    try (Table t1 = new Table.TestBuilder()
            .column(   1,    1,    1,    1,    1,    1)
            .column(   0,    1,    2,    2,    3,    3)
            .column(17.0, 14.0, 14.0, 17.0, 17.1, 17.0)
            .build()) {
      try (Table t3 = t1.groupBy(0, 1)
              .aggregate(GroupByAggregation.argMax().onColumn(2));
           Table sorted = t3
              .orderBy(OrderByArg.asc(0), OrderByArg.asc(1), OrderByArg.asc(2));
           Table expected = new Table.TestBuilder()
                   .column(1, 1, 1, 1)
                   .column(0, 1, 2, 3)
                   .column(0, 1, 3, 4)
                   .build()) {
        assertTablesAreEqual(expected, sorted);
      }
    }
  }

  @Test
  void testGroupByArgMin() {
    // ArgMin is a sort based aggregation
    try (Table t1 = new Table.TestBuilder()
            .column(   1,    1,    1,    1,    1,    1)
            .column(   0,    1,    2,    2,    3,    3)
            .column(17.0, 14.0, 14.0, 17.0, 17.1, 17.0)
            .build()) {
      try (Table t3 = t1.groupBy(0, 1)
              .aggregate(GroupByAggregation.argMin().onColumn(2));
           Table sorted = t3
                   .orderBy(OrderByArg.asc(0), OrderByArg.asc(1), OrderByArg.asc(2));
           Table expected = new Table.TestBuilder()
                   .column(1, 1, 1, 1)
                   .column(0, 1, 2, 3)
                   .column(0, 1, 2, 5)
                   .build()) {
        assertTablesAreEqual(expected, sorted);
      }
    }
  }

  @Test
  void testGroupByMinBool() {
    try (Table t1 = new Table.TestBuilder()
        .column(true, null, false, true, null, null)
        .column(   1,    1,     2,    2,    3,    3).build();
         Table other = t1.groupBy(1).aggregate(GroupByAggregation.min().onColumn(0));
         Table ordered = other.orderBy(OrderByArg.asc(0));
         Table expected = new Table.TestBuilder()
             .column(1, 2, 3)
             .column (true, false, null)
             .build()) {
      assertTablesAreEqual(expected, ordered);
    }
  }

  @Test
  void testGroupByMaxBool() {
    try (Table t1 = new Table.TestBuilder()
        .column(false, null, false, true, null, null)
        .column(   1,    1,     2,    2,    3,    3).build();
         Table other = t1.groupBy(1).aggregate(GroupByAggregation.max().onColumn(0));
         Table ordered = other.orderBy(OrderByArg.asc(0));
         Table expected = new Table.TestBuilder()
             .column(1, 2, 3)
             .column (false, true, null)
             .build()) {
      assertTablesAreEqual(expected, ordered);
    }
  }

  @Test
  void testGroupByDuplicateAggregates() {
    try (Table t1 = new Table.TestBuilder().column(   1,    1,    1,    1,    1,    1)
                                           .column(   1,    3,    3,    5,    5,    0)
                                           .column(12.0, 14.0, 13.0, 15.0, 17.0, 18.0)
                                           .build();
         Table expected = new Table.TestBuilder()
             .column(1, 1, 1, 1)
             .column(1, 3, 5, 0)
             .column(12.0, 14.0, 17.0, 18.0)
             .column(12.0, 13.0, 15.0, 18.0)
             .column(12.0, 13.0, 15.0, 18.0)
             .column(12.0, 14.0, 17.0, 18.0)
             .column(12.0, 13.0, 15.0, 18.0)
             .column(   1,    2,    2,    1).build()) {
      try (Table t3 = t1.groupBy(0, 1)
          .aggregate(
              GroupByAggregation.max().onColumn(2),
              GroupByAggregation.min().onColumn(2),
              GroupByAggregation.min().onColumn(2),
              GroupByAggregation.max().onColumn(2),
              GroupByAggregation.min().onColumn(2),
              GroupByAggregation.count().onColumn(1));
          Table t4 = t3.orderBy(OrderByArg.asc(2))) {
        // verify t4
        assertEquals(4, t4.getRowCount());
        assertTablesAreEqual(t4, expected);

        assertEquals(t3.getColumn(0).getRefCount(), 1);
        assertEquals(t3.getColumn(1).getRefCount(), 1);
        assertEquals(t3.getColumn(2).getRefCount(), 2);
        assertEquals(t3.getColumn(3).getRefCount(), 3);
        assertEquals(t3.getColumn(4).getRefCount(), 3);
        assertEquals(t3.getColumn(5).getRefCount(), 2);
        assertEquals(t3.getColumn(6).getRefCount(), 3);
      }
    }
  }

  @Test
  void testGroupByMin() {
    try (Table t1 = new Table.TestBuilder().column(   1,    1,    1,    1,    1,    1)
                                           .column(   1,    3,    3,    5,    5,    0)
                                           .column(  12,   14,   13,   17,   17,   17)
                                           .build()) {
      try (Table t3 = t1.groupBy(0, 1).aggregate(GroupByAggregation.min().onColumn(2));
           HostColumnVector aggOut0 = t3.getColumn(2).copyToHost()) {
        // verify t3
        assertEquals(4, t3.getRowCount());
        Map<Integer, Integer> expectedAggregateResult = new HashMap() {
          {
            // value, count
            put(12, 1);
            put(13, 1);
            put(17, 2);
          }
        };
        // check to see the aggregate column type depends on the source column
        // in this case the source column is Integer, therefore the result should be Integer type
        assertEquals(DType.INT32, aggOut0.getType());
        for (int i = 0; i < 4; ++i) {
          int key = aggOut0.getInt(i);
          assertTrue(expectedAggregateResult.containsKey(key));
          Integer count = expectedAggregateResult.get(key);
          if (count == 1) {
            expectedAggregateResult.remove(key);
          } else {
            expectedAggregateResult.put(key, count - 1);
          }
        }
      }
    }
  }

  @Test
  void testGroupBySum() {
    try (Table t1 = new Table.TestBuilder().column(   1,    1,    1,    1,    1,    1)
                                           .column(   1,    3,    3,    5,    5,    0)
                                           .column(12.0, 14.0, 13.0, 17.0, 17.0, 17.0)
                                           .build()) {
      try (Table t3 = t1.groupBy(0, 1).aggregate(GroupByAggregation.sum().onColumn(2));
           HostColumnVector aggOut1 = t3.getColumn(2).copyToHost()) {
        // verify t3
        assertEquals(4, t3.getRowCount());
        Map<Double, Integer> expectedAggregateResult = new HashMap() {
          {
            // value, count
            put(12.0, 1);
            put(27.0, 1);
            put(34.0, 1);
            put(17.0, 1);
          }
        };
        for (int i = 0; i < 4; ++i) {
          Double key = aggOut1.getDouble(i);
          assertTrue(expectedAggregateResult.containsKey(key));
          Integer count = expectedAggregateResult.get(key);
          if (count == 1) {
            expectedAggregateResult.remove(key);
          } else {
            expectedAggregateResult.put(key, count - 1);
          }
        }
      }
    }
  }

  @Test
  void testGroupByM2() {
    // A trivial test:
    try (Table input = new Table.TestBuilder().column(1, 2, 3, 1, 2, 2, 1, 3, 3, 2)
             .column(0, 1, -2, 3, -4, -5, -6, 7, -8, 9)
             .build();
         Table results = input.groupBy(0).aggregate(GroupByAggregation.M2()
               .onColumn(1));
         Table expected = new Table.TestBuilder().column(1, 2, 3)
             .column(42.0, 122.75, 114.0)
             .build()) {
      assertTablesAreEqual(expected, results);
    }

    // Test with values have nulls (the values associated with key=2 has both nulls and non-nulls,
    // while the values associated with key=5 are all nulls):
    try (Table input = new Table.TestBuilder().column(1, 2, 5, 3, 4, 5, 2, 3, 2, 5)
             .column(0, null, null, 2, 3, null, 5, 6, 7, null)
             .build();
         Table results = input.groupBy(0).aggregate(GroupByAggregation.M2()
             .onColumn(1));
         Table expected = new Table.TestBuilder().column(1, 2, 3, 4, 5)
             .column(0.0, 2.0, 8.0, 0.0, null)
             .build()) {
      assertTablesAreEqual(expected, results);
    }

    // Test with floating-point values having NaN:
    try (Table input = new Table.TestBuilder().column(4, 3, 1, 2, 3, 1, 2, 2, 1, null, 3, 2, 4, 4)
             .column(null, null, 0.0, 1.0, 2.0, 3.0, 4.0, Double.NaN, 6.0, 7.0, 8.0, 9.0, 10.0, Double.NaN)
             .build();
         Table results = input.groupBy(0).aggregate(GroupByAggregation.M2()
             .onColumn(1));
         Table expected = new Table.TestBuilder().column(1, 2, 3, 4, null)
             .column(18.0, Double.NaN, 18.0, Double.NaN, 0.0)
             .build()) {
      assertTablesAreEqual(expected, results);
    }

    // Test with floating-point values having NaN and +/- Inf
    // (The values associated with:
    //   key=1: have only NaN
    //   key=2: have only +Inf
    //   key=3: have only -Inf
    //   key=4: have NaN and +/- Inf,
    //   key=5: have normal numbers):
    try (Table input = new Table.TestBuilder().column(1, 2, 3, 4, 5, 1, 2, 3, 4, 5, 1, 2, 3, 4)
             .column(Double.NaN,
                     Double.POSITIVE_INFINITY,
                     Double.NEGATIVE_INFINITY,
                     Double.POSITIVE_INFINITY,
                     5.0,
                     //
                     Double.NaN,
                     Double.POSITIVE_INFINITY,
                     Double.NEGATIVE_INFINITY,
                     Double.NEGATIVE_INFINITY,
                     10.0,
                     //
                     Double.NaN,
                     Double.POSITIVE_INFINITY,
                     Double.NEGATIVE_INFINITY,
                     Double.POSITIVE_INFINITY)
             .build();
         Table results = input.groupBy(0).aggregate(GroupByAggregation.M2()
             .onColumn(1));
         Table expected = new Table.TestBuilder().column(1, 2, 3, 4, 5)
             .column(Double.NaN, Double.NaN, Double.NaN, Double.NaN, 12.5)
             .build()) {
      assertTablesAreEqual(expected, results);
    }
  }

  @Test
  void testGroupByMergeM2() {
    StructType nestedType = new StructType(false,
        new BasicType(true, DType.INT32),
        new BasicType(true, DType.FLOAT64),
        new BasicType(true, DType.FLOAT64));

    try (Table partialResults1 = new Table.TestBuilder()
             .column(1, 2, 3, 4)
             .column(nestedType,
                 struct(1, 0.0, 0.0),
                 struct(1, 1.0, 0.0),
                 struct(0, null, null),
                 struct(0, null, null))
             .build();
         Table partialResults2 = new Table.TestBuilder()
             .column(1, 2, 3)
             .column(nestedType,
                 struct(1, 3.0, 0.0),
                 struct(1, 4.0, 0.0),
                 struct(1, 2.0, 0.0))
             .build();
         Table partialResults3 = new Table.TestBuilder()
             .column(1, 2)
             .column(nestedType,
                 struct(1, 6.0, 0.0),
                 struct(1, Double.NaN, Double.NaN))
             .build();
         Table partialResults4 = new Table.TestBuilder()
             .column(2, 3, 4)
             .column(nestedType,
                 struct(1, 9.0, 0.0),
                 struct(1, 8.0, 0.0),
                 struct(2, Double.NaN, Double.NaN))
             .build();
         Table expected = new Table.TestBuilder()
             .column(1, 2, 3, 4)
             .column(nestedType,
                 struct(3, 3.0, 18.0),
                 struct(4, Double.NaN, Double.NaN),
                 struct(2, 5.0, 18.0),
                 struct(2, Double.NaN, Double.NaN))
             .build()) {
      try (Table concatenatedResults = Table.concatenate(
             partialResults1,
             partialResults2,
             partialResults3,
             partialResults4);
           Table finalResults = concatenatedResults.groupBy(0).aggregate(
               GroupByAggregation.mergeM2().onColumn(1))
           ) {
        assertTablesAreEqual(expected, finalResults);
      }
    }
  }

  @Test
  void testGroupByFirstExcludeNulls() {
    try (Table input = new Table.TestBuilder()
            .column(  1,   1,    1,  1,  2,    2,  2,    2)
            .column(null, 13, null, 12, 14, null, 15, null)
            .build();
         Table expected = new Table.TestBuilder()
                 .column(1, 2)
                 .column(13, 14)
                 .build();
         Table found = input.groupBy(0).aggregate(
             GroupByAggregation.nth(0, NullPolicy.EXCLUDE).onColumn(1))) {
      assertTablesAreEqual(expected, found);
    }
  }

  @Test
  void testGroupByLastExcludeNulls() {
    try (Table input = new Table.TestBuilder()
            .column(  1,   1,    1,  1,  2,    2,  2,    2)
            .column(null, 13, null, 12, 14, null, 15, null)
            .build();
         Table expected = new Table.TestBuilder()
                 .column(1, 2)
                 .column(12, 15)
                 .build();
         Table found = input.groupBy(0).aggregate(
             GroupByAggregation.nth(-1, NullPolicy.EXCLUDE).onColumn(1))) {
      assertTablesAreEqual(expected, found);
    }
  }

  @Test
  void testGroupByFirstIncludeNulls() {
    try (Table input = new Table.TestBuilder()
            .column(  1,   1,    1,  1,  2,    2,  2,    2)
            .column(null, 13, null, 12, 14, null, 15, null)
            .build();
         Table expected = new Table.TestBuilder()
                 .column(1, 2)
                 .column(null, 14)
                 .build();
         Table found = input.groupBy(0).aggregate(
             GroupByAggregation.nth(0, NullPolicy.INCLUDE).onColumn(1))) {
      assertTablesAreEqual(expected, found);
    }
  }

  @Test
  void testGroupByLastIncludeNulls() {
    try (Table input = new Table.TestBuilder()
            .column(  1,   1,    1,  1,  2,    2,  2,    2)
            .column(null, 13, null, 12, 14, null, 15, null)
            .build();
         Table expected = new Table.TestBuilder()
                 .column(1, 2)
                 .column(12, null)
                 .build();
         Table found = input.groupBy(0).aggregate(
             GroupByAggregation.nth(-1, NullPolicy.INCLUDE).onColumn(1))) {
      assertTablesAreEqual(expected, found);
    }
  }

  @Test
  void testGroupByAvg() {
    try (Table t1 = new Table.TestBuilder().column( 1,  1,  1,  1,  1,  1)
                                           .column( 1,  3,  3,  5,  5,  0)
                                           .column(12, 14, 13,  1, 17, 17)
                                           .build()) {
      try (Table t3 = t1.groupBy(0, 1).aggregate(GroupByAggregation.mean().onColumn(2));
           HostColumnVector aggOut1 = t3.getColumn(2).copyToHost()) {
        // verify t3
        assertEquals(4, t3.getRowCount());
        Map<Double, Integer> expectedAggregateResult = new HashMap() {
          {
            // value, count
            put(12.0, 1);
            put(13.5, 1);
            put(17.0, 1);
            put(9.0, 1);
          }
        };
        for (int i = 0; i < 4; ++i) {
          Double key = aggOut1.getDouble(i);
          assertTrue(expectedAggregateResult.containsKey(key));
          Integer count = expectedAggregateResult.get(key);
          if (count == 1) {
            expectedAggregateResult.remove(key);
          } else {
            expectedAggregateResult.put(key, count - 1);
          }
        }
      }
    }
  }

  @Test
  void testMultiAgg() {
    try (Table t1 = new Table.TestBuilder().column(  1,   1,   1,   1,   1,    1)
                                           .column(  2,   2,   2,   3,   3,    3)
                                           .column(5.0, 2.3, 3.4, 2.3, 1.3, 12.2)
                                           .column(  3,   1,   7,  -1,   9,    0)
                                           .build()) {
      try (Table t2 = t1.groupBy(0, 1).aggregate(
          GroupByAggregation.count().onColumn(0),
          GroupByAggregation.max().onColumn(3),
          GroupByAggregation.min().onColumn(2),
          GroupByAggregation.mean().onColumn(2),
          GroupByAggregation.sum().onColumn(2));
           HostColumnVector countOut = t2.getColumn(2).copyToHost();
           HostColumnVector maxOut = t2.getColumn(3).copyToHost();
           HostColumnVector minOut = t2.getColumn(4).copyToHost();
           HostColumnVector avgOut = t2.getColumn(5).copyToHost();
           HostColumnVector sumOut = t2.getColumn(6).copyToHost()) {
        assertEquals(2, t2.getRowCount());

        // verify count
        assertEquals(3, countOut.getInt(0));
        assertEquals(3, countOut.getInt(1));

        // verify mean
        List<Double> sortedMean = new ArrayList<>();
        sortedMean.add(avgOut.getDouble(0));
        sortedMean.add(avgOut.getDouble(1));
        sortedMean = sortedMean.stream()
            .sorted(Comparator.naturalOrder())
            .collect(Collectors.toList());

        assertEqualsWithinPercentage(3.5666f, sortedMean.get(0), 0.0001);
        assertEqualsWithinPercentage(5.2666f, sortedMean.get(1), 0.0001);

        // verify sum
        List<Double> sortedSum = new ArrayList<>();
        sortedSum.add(sumOut.getDouble(0));
        sortedSum.add(sumOut.getDouble(1));
        sortedSum = sortedSum.stream()
            .sorted(Comparator.naturalOrder())
            .collect(Collectors.toList());

        assertEqualsWithinPercentage(10.7f, sortedSum.get(0), 0.0001);
        assertEqualsWithinPercentage(15.8f, sortedSum.get(1), 0.0001);

        // verify min
        List<Double> sortedMin = new ArrayList<>();
        sortedMin.add(minOut.getDouble(0));
        sortedMin.add(minOut.getDouble(1));
        sortedMin = sortedMin.stream()
            .sorted(Comparator.naturalOrder())
            .collect(Collectors.toList());

        assertEqualsWithinPercentage(1.3f, sortedMin.get(0), 0.0001);
        assertEqualsWithinPercentage(2.3f, sortedMin.get(1), 0.0001);

        // verify max
        List<Integer> sortedMax = new ArrayList<>();
        sortedMax.add(maxOut.getInt(0));
        sortedMax.add(maxOut.getInt(1));
        sortedMax = sortedMax.stream()
            .sorted(Comparator.naturalOrder())
            .collect(Collectors.toList());

        assertEquals(7, sortedMax.get(0));
        assertEquals(9, sortedMax.get(1));
      }
    }
  }

  @Test
  void testSumWithStrings() {
    try (Table t = new Table.TestBuilder()
        .column("1-URGENT", "3-MEDIUM", "1-URGENT", "3-MEDIUM")
        .column(5289L, 5203L, 5303L, 5206L)
        .build();
         Table result = t.groupBy(0).aggregate(
             GroupByAggregation.sum().onColumn(1));
         Table expected = new Table.TestBuilder()
             .column("1-URGENT", "3-MEDIUM")
             .column(5289L + 5303L, 5203L + 5206L)
             .build()) {
      assertTablesAreEqual(expected, result);
    }
  }

  @Test
  void testGroupByNoAggs() {
    try (Table t1 = new Table.TestBuilder().column(   1,    1,    1,    1,    1,    1)
        .column(   1,    3,    3,    5,    5,    0)
        .column(  12,   14,   13,   17,   17,   17)
        .decimal32Column(-3,   12,   14,   13,   111,   222,   333)
        .decimal64Column(-3,   12L,   14L,   13L,   111L,   222L,   333L)
        .build()) {
      try (Table t3 = t1.groupBy(0, 1).aggregate()) {
        // verify t3
        assertEquals(4, t3.getRowCount());
      }
    }
  }

  @Test
  void testGroupByContiguousSplitGroups() {
    ContiguousTable[] splits = null;
    try (Table table = new Table.TestBuilder()
        .column(   1,    1,    1,    1,    1,    1)
        .column(   1,    3,    3,    5,    5,    5)
        .column(  12,   14,   13,   17,   16,   18)
        .column("s1", "s2", "s3", "s4", "s5", "s6")
        .build()) {
      // Normal case with primitive types.
      try (Table expected1 = new Table.TestBuilder()
              .column(   1)
              .column(   1)
              .column(  12)
              .column("s1").build();
           Table expected2 = new Table.TestBuilder()
              .column(   1,    1)
              .column(   3,    3)
              .column(  14,   13)
              .column("s2", "s3").build();
           Table expected3 = new Table.TestBuilder()
              .column(   1,    1,    1)
              .column(   5,    5,    5)
              .column(  17,   16,   18)
              .column("s4", "s5", "s6").build()) {
        try {
          splits = table.groupBy(0, 1).contiguousSplitGroups();
          assertEquals(3, splits.length);
          for (ContiguousTable ct : splits) {
            if (ct.getRowCount() == 1) {
              assertTablesAreEqual(expected1, ct.getTable());
            } else if (ct.getRowCount() == 2) {
              assertTablesAreEqual(expected2, ct.getTable());
            } else {
              assertTablesAreEqual(expected3, ct.getTable());
            }
          }
        } finally {
          if (splits != null) {
            for (ContiguousTable t : splits) { t.close(); }
          }
          splits = null;
        }
      }

      // Empty key columns, the whole table is a group.
      try {
        splits = table.groupBy().contiguousSplitGroups();
        assertEquals(1, splits.length);
        assertTablesAreEqual(table, splits[0].getTable());
      } finally {
        if (splits != null) {
          for (ContiguousTable t : splits) { t.close(); }
        }
      }

    }
  }

  @Test
  void testGroupByCollectListIncludeNulls() {
    try (Table input = new Table.TestBuilder()
        .column(1, 1, 1, 1, 2, 2, 2, 2, 3, 4)
        .column(null, 13, null, 12, 14, null, 15, null, null, 0)
        .build();
         Table expected = new Table.TestBuilder()
             .column(1, 2, 3, 4)
             .column(new ListType(false, new BasicType(true, DType.INT32)),
                 Arrays.asList(null, 13, null, 12),
                 Arrays.asList(14, null, 15, null),
                 Arrays.asList((Integer) null),
                 Arrays.asList(0))
             .build();
         Table found = input.groupBy(0).aggregate(
             GroupByAggregation.collectList(NullPolicy.INCLUDE).onColumn(1))) {
      assertTablesAreEqual(expected, found);
    }
  }

  @Test
  void testGroupByMergeLists() {
    ListType listOfInts = new ListType(false, new BasicType(false, DType.INT32));
    ListType listOfStructs = new ListType(false, new StructType(false,
        new BasicType(false, DType.INT32), new BasicType(false, DType.STRING)));
    try (Table input = new Table.TestBuilder()
        .column(1, 1, 1, 1, 2, 2, 2, 2, 3, 4)
        .column(listOfInts,
            Arrays.asList(1, 2), Arrays.asList(3), Arrays.asList(7, 8), Arrays.asList(4, 5, 6),
            Arrays.asList(8, 9), Arrays.asList(8, 9, 10), Arrays.asList(10, 11), Arrays.asList(11, 12),
            Arrays.asList(13, 13), Arrays.asList(14, 15, 15))
        .column(listOfStructs,
            Arrays.asList(new StructData(1, "s1"), new StructData(2, "s2")),
            Arrays.asList(new StructData(2, "s3"), new StructData(3, "s4")),
            Arrays.asList(new StructData(2, "s2")),
            Arrays.asList(),
            Arrays.asList(new StructData(11, "s11")),
            Arrays.asList(new StructData(22, "s22"), new StructData(33, "s33")),
            Arrays.asList(),
            Arrays.asList(new StructData(22, "s22"), new StructData(33, "s33"), new StructData(44, "s44")),
            Arrays.asList(new StructData(333, "s333"), new StructData(222, "s222"), new StructData(111, "s111")),
            Arrays.asList(new StructData(222, "s222"), new StructData(444, "s444")))
        .build();
         Table expectedListOfInts = new Table.TestBuilder()
             .column(1, 2, 3, 4)
             .column(listOfInts,
                 Arrays.asList(1, 2, 3, 7 ,8, 4, 5, 6),
                 Arrays.asList(8, 9, 8, 9, 10, 10, 11, 11, 12),
                 Arrays.asList(13, 13),
                 Arrays.asList(14, 15, 15))
             .build();
         Table expectedListOfStructs = new Table.TestBuilder()
             .column(1, 2, 3, 4)
             .column(listOfStructs,
                 Arrays.asList(new StructData(1, "s1"), new StructData(2, "s2"),
                     new StructData(2, "s3"), new StructData(3, "s4"), new StructData(2, "s2")),
                 Arrays.asList(new StructData(11, "s11"), new StructData(22, "s22"), new StructData(33, "s33"),
                     new StructData(22, "s22"), new StructData(33, "s33"), new StructData(44, "s44")),
                 Arrays.asList(new StructData(333, "s333"), new StructData(222, "s222"), new StructData(111, "s111")),
                 Arrays.asList(new StructData(222, "s222"), new StructData(444, "s444")))
             .build();
         Table retListOfInts = input.groupBy(0).aggregate(GroupByAggregation.mergeLists().onColumn(1));
         Table retListOfStructs = input.groupBy(0).aggregate(GroupByAggregation.mergeLists().onColumn(2))) {
      assertTablesAreEqual(expectedListOfInts, retListOfInts);
      assertTablesAreEqual(expectedListOfStructs, retListOfStructs);
    }
  }

  @Test
  void testGroupByCollectSetIncludeNulls() {
    // test with null unequal and nan unequal
    GroupByAggregation collectSet = GroupByAggregation.collectSet(NullPolicy.INCLUDE,
        NullEquality.UNEQUAL, NaNEquality.UNEQUAL);
    try (Table input = new Table.TestBuilder()
        .column(1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4)
        .column(null, 13, null, 13, 14, null, 15, null, 4, 1, 1, 4, 0, 0, 0, 0)
        .build();
         Table expected = new Table.TestBuilder()
             .column(1, 2, 3, 4)
             .column(new ListType(false, new BasicType(true, DType.INT32)),
                 Arrays.asList(13, null, null), Arrays.asList(14, 15, null, null),
                 Arrays.asList(1, 4), Arrays.asList(0))
             .build();
         Table found = input.groupBy(0).aggregate(collectSet.onColumn(1))) {
      assertTablesAreEqual(expected, found);
    }
    // test with null equal and nan unequal
    collectSet = GroupByAggregation.collectSet(NullPolicy.INCLUDE,
        NullEquality.EQUAL, NaNEquality.UNEQUAL);
    try (Table input = new Table.TestBuilder()
        .column(1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4)
        .column(null, 13.0, null, 13.0,
            14.1, Double.NaN, 13.9, Double.NaN,
            Double.NaN, null, 1.0, null,
            null, null, null, null)
        .build();
         Table expected = new Table.TestBuilder()
             .column(1, 2, 3, 4)
             .column(new ListType(false, new BasicType(true, DType.FLOAT64)),
                 Arrays.asList(13.0, null),
                 Arrays.asList(13.9, 14.1, Double.NaN, Double.NaN),
                 Arrays.asList(1.0, Double.NaN, null),
                 Arrays.asList((Integer) null))
             .build();
         Table found = input.groupBy(0).aggregate(collectSet.onColumn(1))) {
      assertTablesAreEqual(expected, found);
    }
    // test with null equal and nan equal
    collectSet = GroupByAggregation.collectSet(NullPolicy.INCLUDE,
        NullEquality.EQUAL, NaNEquality.ALL_EQUAL);
    try (Table input = new Table.TestBuilder()
        .column(1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4)
        .column(null, 13.0, null, 13.0,
            14.1, Double.NaN, 13.9, Double.NaN,
            0.0, 0.0, 0.00, 0.0,
            Double.NaN, Double.NaN, null, null)
        .build();
         Table expected = new Table.TestBuilder()
             .column(1, 2, 3, 4)
             .column(new ListType(false, new BasicType(true, DType.FLOAT64)),
                 Arrays.asList(13.0, null),
                 Arrays.asList(13.9, 14.1, Double.NaN),
                 Arrays.asList(0.0),
                 Arrays.asList(Double.NaN, (Integer) null))
             .build();
         Table found = input.groupBy(0).aggregate(collectSet.onColumn(1))) {
      assertTablesAreEqual(expected, found);
    }
  }

  @Test
  void testGroupByMergeSets() {
    ListType listOfInts = new ListType(false, new BasicType(false, DType.INT32));
    ListType listOfDoubles = new ListType(false, new BasicType(false, DType.FLOAT64));
    try (Table input = new Table.TestBuilder()
        .column(1, 1, 1, 1, 2, 2, 2, 2, 3, 4)
        .column(listOfInts,
            Arrays.asList(1, 2), Arrays.asList(3), Arrays.asList(7, 8), Arrays.asList(4, 5, 6),
            Arrays.asList(8, 9), Arrays.asList(8, 9, 10), Arrays.asList(10, 11), Arrays.asList(11, 12),
            Arrays.asList(13, 13), Arrays.asList(14, 15, 15))
        .column(listOfDoubles,
            Arrays.asList(Double.NaN, 1.2), Arrays.asList(), Arrays.asList(Double.NaN), Arrays.asList(-3e10),
            Arrays.asList(1.1, 2.2, 3.3), Arrays.asList(3.3, 2.2), Arrays.asList(), Arrays.asList(),
            Arrays.asList(1e3, Double.NaN, 1e-3, Double.NaN), Arrays.asList())
        .build();
         Table expectedListOfInts = new Table.TestBuilder()
             .column(1, 2, 3, 4)
             .column(listOfInts,
                 Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8),
                 Arrays.asList(8, 9, 10, 11, 12),
                 Arrays.asList(13),
                 Arrays.asList(14, 15))
             .build();
         Table expectedListOfDoubles = new Table.TestBuilder()
             .column(1, 2, 3, 4)
             .column(listOfDoubles,
                 Arrays.asList(-3e10, 1.2, Double.NaN, Double.NaN),
                 Arrays.asList(1.1, 2.2, 3.3),
                 Arrays.asList(1e-3, 1e3, Double.NaN, Double.NaN),
                 Arrays.asList())
             .build();
         Table expectedListOfDoublesNaNEq = new Table.TestBuilder()
             .column(1, 2, 3, 4)
             .column(listOfDoubles,
                 Arrays.asList(-3e10, 1.2, Double.NaN),
                 Arrays.asList(1.1, 2.2, 3.3),
                 Arrays.asList(1e-3, 1e3, Double.NaN),
                 Arrays.asList())
             .build();
         Table retListOfInts = input.groupBy(0).aggregate(GroupByAggregation.mergeSets().onColumn(1));
         Table retListOfDoubles = input.groupBy(0).aggregate(GroupByAggregation.mergeSets().onColumn(2));
         Table retListOfDoublesNaNEq = input.groupBy(0).aggregate(
             GroupByAggregation.mergeSets(NullEquality.UNEQUAL, NaNEquality.ALL_EQUAL).onColumn(2))) {
      assertTablesAreEqual(expectedListOfInts, retListOfInts);
      assertTablesAreEqual(expectedListOfDoubles, retListOfDoubles);
      assertTablesAreEqual(expectedListOfDoublesNaNEq, retListOfDoublesNaNEq);
    }
  }

  @Test
  void testRowBitCount() {
    try (Table t = new Table.TestBuilder()
        .column(0, 1, null, 3)                 // 33 bits per row (4 bytes + valid bit)
        .column(0.0, null, 2.0, 3.0)           // 65 bits per row (8 bytes + valid bit)
        .column("zero", null, "two", "three")  // 33 bits (4 byte offset + valid bit) + char bits
        .build();
         ColumnVector expected = ColumnVector.fromInts(163, 131, 155, 171);
         ColumnVector actual = t.rowBitCount()) {
      assertColumnsAreEqual(expected, actual);
    }
  }

  @Test
  void testRowBitCountEmpty() {
    try (Table t = new Table.TestBuilder()
            .column(new Integer[0])
            .column(new Double[0])
            .column(new String[0])
            .build();
         ColumnVector c = t.rowBitCount()) {
      assertEquals(DType.INT32, c.getType());
      assertEquals(0, c.getRowCount());
    }
  }

  @Test
  void testSimpleGather() {
    try (Table testTable = new Table.TestBuilder()
            .column(1, 2, 3, 4, 5)
            .column("A", "AA", "AAA", "AAAA", "AAAAA")
            .decimal32Column(-3, 1, 2, 3, 4, 5)
            .decimal64Column(-8, 100001L, 200002L, 300003L, 400004L, 500005L)
            .build();
         ColumnVector gatherMap = ColumnVector.fromInts(0, 2, 4, -2);
         Table expected = new Table.TestBuilder()
                 .column(1, 3, 5, 4)
                 .column("A", "AAA", "AAAAA", "AAAA")
                 .decimal32Column(-3, 1, 3, 5, 4)
                 .decimal64Column(-8, 100001L, 300003L, 500005L, 400004L)
                 .build();
         Table found = testTable.gather(gatherMap)) {
      assertTablesAreEqual(expected, found);
    }
  }

  @Test
  void testBoundsCheckedGather() {
    try (Table testTable = new Table.TestBuilder()
            .column(1, 2, 3, 4, 5)
            .column("A", "AA", "AAA", "AAAA", "AAAAA")
            .decimal32Column(-3, 1, 2, 3, 4, 5)
            .decimal64Column(-8, 100001L, 200002L, 300003L, 400004L, 500005L)
            .build();
         ColumnVector gatherMap = ColumnVector.fromInts(0, 100, 4, -2);
         Table expected = new Table.TestBuilder()
                 .column(1, null, 5, 4)
                 .column("A", null, "AAAAA", "AAAA")
                 .decimal32Column(-3, 1, null, 5, 4)
                 .decimal64Column(-8, 100001L, null, 500005L, 400004L)
                 .build();
         Table found = testTable.gather(gatherMap)) {
      assertTablesAreEqual(expected, found);
    }
  }

  @Test
  void testMaskWithoutValidity() {
    try (ColumnVector mask = ColumnVector.fromBoxedBooleans(true, false, true, false, true);
         ColumnVector fromInts = ColumnVector.fromInts(1, 2, 3, 4, 5);
         ColumnVector fromStrings = ColumnVector.fromStrings("1", "2", "3", "4", "5");
         ColumnVector fromDecimals = ColumnVector.decimalFromLongs(-3, 123L, -234L, 345L, 1000L, -2000L);
         Table input = new Table(fromInts, fromStrings, fromDecimals);
         Table filteredTable = input.filter(mask);
         ColumnVector expectedInts = ColumnVector.fromInts(1, 3, 5);
         ColumnVector expectedStrings = ColumnVector.fromStrings("1", "3", "5");
         ColumnVector expectedDecimals = ColumnVector.decimalFromLongs(-3, 123L, 345L, -2000L);
         Table expected = new Table(expectedInts, expectedStrings, expectedDecimals)) {
      assertTablesAreEqual(expected, filteredTable);
    }
  }

  @Test
  void testMaskWithValidity() {
    final int numRows = 5;
    try (Builder builder = HostColumnVector.builder(DType.BOOL8, numRows)) {
      for (int i = 0; i < numRows; ++i) {
        builder.append((byte) 1);
        if (i % 2 != 0) {
          builder.setNullAt(i);
        }
      }
      try (ColumnVector mask = builder.buildAndPutOnDevice();
           ColumnVector fromInts = ColumnVector.fromBoxedInts(1, null, 2, 3, null);
           Table input = new Table(fromInts);
           Table filteredTable = input.filter(mask);
           HostColumnVector filtered = filteredTable.getColumn(0).copyToHost()) {
        assertEquals(DType.INT32, filtered.getType());
        assertEquals(3, filtered.getRowCount());
        assertEquals(1, filtered.getInt(0));
        assertEquals(2, filtered.getInt(1));
        assertTrue(filtered.isNull(2));
      }
    }
  }

  @Test
  void testMaskDataOnly() {
    byte[] maskVals = new byte[]{0, 1, 0, 1, 1};
    try (ColumnVector mask = ColumnVector.boolFromBytes(maskVals);
         ColumnVector fromBytes = ColumnVector.fromBoxedBytes((byte) 1, null, (byte) 2, (byte) 3, null);
         Table input = new Table(fromBytes);
         Table filteredTable = input.filter(mask);
         HostColumnVector filtered = filteredTable.getColumn(0).copyToHost()) {
      assertEquals(DType.INT8, filtered.getType());
      assertEquals(3, filtered.getRowCount());
      assertTrue(filtered.isNull(0));
      assertEquals(3, filtered.getByte(1));
      assertTrue(filtered.isNull(2));
    }
  }


  @Test
  void testAllFilteredFromData() {
    Boolean[] maskVals = new Boolean[5];
    Arrays.fill(maskVals, false);
    try (ColumnVector mask = ColumnVector.fromBoxedBooleans(maskVals);
         ColumnVector fromInts = ColumnVector.fromBoxedInts(1, null, 2, 3, null);
         ColumnVector fromDecimal32s = ColumnVector.decimalFromInts(-3, 1, 2, 3, 4, 5);
         ColumnVector fromDecimal64s = ColumnVector.decimalFromLongs(-11, 1L, 2L, 3L, 4L, 5L);
         Table input = new Table(fromInts, fromDecimal32s, fromDecimal64s);
         Table filteredTable = input.filter(mask)) {
      ColumnVector filtered = filteredTable.getColumn(0);
      assertEquals(DType.INT32, filtered.getType());
      assertEquals(0, filtered.getRowCount());
      filtered = filteredTable.getColumn(1);
      assertEquals(DType.create(DType.DTypeEnum.DECIMAL32, -3), filtered.getType());
      assertEquals(0, filtered.getRowCount());
      filtered = filteredTable.getColumn(2);
      assertEquals(DType.create(DType.DTypeEnum.DECIMAL64, -11), filtered.getType());
      assertEquals(0, filtered.getRowCount());
    }
  }

  @Test
  void testAllFilteredFromValidity() {
    final int numRows = 5;
    try (Builder builder = HostColumnVector.builder(DType.BOOL8, numRows)) {
      for (int i = 0; i < numRows; ++i) {
        builder.append((byte) 1);
        builder.setNullAt(i);
      }
      try (ColumnVector mask = builder.buildAndPutOnDevice();
           ColumnVector fromInts = ColumnVector.fromBoxedInts(1, null, 2, 3, null);
           ColumnVector fromDecimal32s = ColumnVector.decimalFromInts(-3, 1, 2, 3, 4, 5);
           ColumnVector fromDecimal64s = ColumnVector.decimalFromLongs(-11, 1L, 2L, 3L, 4L, 5L);
           Table input = new Table(fromInts, fromDecimal32s, fromDecimal64s);
           Table filteredTable = input.filter(mask)) {
        ColumnVector filtered = filteredTable.getColumn(0);
        assertEquals(DType.INT32, filtered.getType());
        assertEquals(0, filtered.getRowCount());
        filtered = filteredTable.getColumn(1);
        assertEquals(DType.create(DType.DTypeEnum.DECIMAL32, -3), filtered.getType());
        assertEquals(0, filtered.getRowCount());
        filtered = filteredTable.getColumn(2);
        assertEquals(DType.create(DType.DTypeEnum.DECIMAL64, -11), filtered.getType());
        assertEquals(0, filtered.getRowCount());
      }
    }
  }

  ColumnView replaceValidity(ColumnView cv, DeviceMemoryBuffer validity, long nullCount) {
    assert (validity.length >= BitVectorHelper.getValidityAllocationSizeInBytes(cv.rows));
    if (cv.type.isNestedType()) {
      ColumnView[] children = cv.getChildColumnViews();
      try {
        return new ColumnView(cv.type,
            cv.rows,
            Optional.of(nullCount),
            validity,
            cv.getOffsets(),
            children);
      } finally {
        for (ColumnView v : children) {
          if (v != null) {
            v.close();
          }
        }
      }
    } else {
      return new ColumnView(cv.type, cv.rows, Optional.of(nullCount), cv.getData(), validity, cv.getOffsets());
    }
  }

  @Test
  void testRemoveNullMasksIfNeeded() {
    ListType nestedType = new ListType(true, new StructType(false,
        new BasicType(true, DType.INT32),
        new BasicType(true, DType.INT64)));

    List data1 = Arrays.asList(10, 20L);
    List data2 = Arrays.asList(50, 60L);
    HostColumnVector.StructData structData1 = new HostColumnVector.StructData(data1);
    HostColumnVector.StructData structData2 = new HostColumnVector.StructData(data2);

    //First we create ColumnVectors
    try (ColumnVector nonNullVector0 = ColumnVector.fromBoxedInts(1, 2, 3);
         ColumnVector nonNullVector2 = ColumnVector.fromStrings("1", "2", "3");
         ColumnVector nonNullVector1 = ColumnVector.fromLists(nestedType,
             Arrays.asList(structData1, structData2),
             Arrays.asList(structData1, structData2),
             Arrays.asList(structData1, structData2))) {
      //Then we take the created ColumnVectors and add validity masks even though the nullCount = 0
      long allocSize = BitVectorHelper.getValidityAllocationSizeInBytes(nonNullVector0.rows);
      try (DeviceMemoryBuffer dm0 = DeviceMemoryBuffer.allocate(allocSize);
           DeviceMemoryBuffer dm1 = DeviceMemoryBuffer.allocate(allocSize);
           DeviceMemoryBuffer dm2 = DeviceMemoryBuffer.allocate(allocSize);
           DeviceMemoryBuffer dm3_child =
               DeviceMemoryBuffer.allocate(BitVectorHelper.getValidityAllocationSizeInBytes(2))) {
        Cuda.memset(dm0.address, (byte) 0xFF, allocSize);
        Cuda.memset(dm1.address, (byte) 0xFF, allocSize);
        Cuda.memset(dm2.address, (byte) 0xFF, allocSize);
        Cuda.memset(dm3_child.address, (byte) 0xFF,
            BitVectorHelper.getValidityAllocationSizeInBytes(2));

        try (ColumnView cv0View = replaceValidity(nonNullVector0, dm0, 0);
             ColumnVector cv0 = cv0View.copyToColumnVector();
             ColumnView struct = nonNullVector1.getChildColumnView(0);
             ColumnView structChild0 = struct.getChildColumnView(0);
             ColumnView newStructChild0 = replaceValidity(structChild0, dm3_child, 0);
             ColumnView newStruct = struct.replaceChildrenWithViews(new int[]{0}, new ColumnView[]{newStructChild0});
             ColumnView list = nonNullVector1.replaceChildrenWithViews(new int[]{0}, new ColumnView[]{newStruct});
             ColumnView cv1View = replaceValidity(list, dm1, 0);
             ColumnVector cv1 = cv1View.copyToColumnVector();
             ColumnView cv2View = replaceValidity(nonNullVector2, dm2, 0);
             ColumnVector cv2 = cv2View.copyToColumnVector()) {

          try (Table t = new Table(new ColumnVector[]{cv0, cv1, cv2});
               Table tableWithoutNullMask = removeNullMasksIfNeeded(t);
               ColumnView tableStructChild0 = t.getColumn(1).getChildColumnView(0).getChildColumnView(0);
               ColumnVector tableStructChild0Cv = tableStructChild0.copyToColumnVector();
               Table expected = new Table(new ColumnVector[]{nonNullVector0, nonNullVector1,
                nonNullVector2})) {
            assertTrue(t.getColumn(0).hasValidityVector());
            assertTrue(t.getColumn(1).hasValidityVector());
            assertTrue(t.getColumn(2).hasValidityVector());
            assertTrue(tableStructChild0Cv.hasValidityVector());

            assertPartialTablesAreEqual(expected,
                0,
                expected.getRowCount(),
                tableWithoutNullMask,
                true,
                true);
          }
        }
      }
    }
  }

  @Test
  void testRemoveNullMasksIfNeededWithNulls() {
    ListType nestedType = new ListType(true, new StructType(true,
        new BasicType(true, DType.INT32),
        new BasicType(true, DType.INT64)));

    List data1 = Arrays.asList(0, 10L);
    List data2 = Arrays.asList(50, null);
    HostColumnVector.StructData structData1 = new HostColumnVector.StructData(data1);
    HostColumnVector.StructData structData2 = new HostColumnVector.StructData(data2);

    //First we create ColumnVectors
    try (ColumnVector nonNullVector0 = ColumnVector.fromBoxedInts(1, null, 2, 3);
         ColumnVector nonNullVector1 = ColumnVector.fromStrings("1", "2", null, "3");
         ColumnVector nonNullVector2 = ColumnVector.fromLists(nestedType,
             Arrays.asList(structData1, structData2),
             null,
             Arrays.asList(structData1, structData2),
             Arrays.asList(structData1, structData2))) {
      try (Table expected = new Table(new ColumnVector[]{nonNullVector0, nonNullVector1, nonNullVector2});
           Table unchangedTable = removeNullMasksIfNeeded(expected)) {
        assertTablesAreEqual(expected, unchangedTable);
      }
    }
  }

  @Test
  void testMismatchedSizesForFilter() {
    Boolean[] maskVals = new Boolean[3];
    Arrays.fill(maskVals, true);
    try (ColumnVector mask = ColumnVector.fromBoxedBooleans(maskVals);
         ColumnVector fromInts = ColumnVector.fromBoxedInts(1, null, 2, 3, null);
         Table input = new Table(fromInts)) {
      assertThrows(AssertionError.class, () -> input.filter(mask).close());
    }
  }

  @Test
  void testTableBasedFilter() {
    byte[] maskVals = new byte[]{0, 1, 0, 1, 1};
    try (ColumnVector mask = ColumnVector.boolFromBytes(maskVals);
         ColumnVector fromInts = ColumnVector.fromBoxedInts(1, null, 2, 3, null);
         ColumnVector fromStrings = ColumnVector.fromStrings("one", "two", "three", null, "five");
         ColumnVector fromDecimals = ColumnVector.fromDecimals(BigDecimal.ZERO, null, BigDecimal.ONE, null, BigDecimal.TEN);
         Table input = new Table(fromInts, fromStrings, fromDecimals);
         Table filtered = input.filter(mask);
         ColumnVector expectedFromInts = ColumnVector.fromBoxedInts(null, 3, null);
         ColumnVector expectedFromStrings = ColumnVector.fromStrings("two", null, "five");
         ColumnVector expectedFromDecimals = ColumnVector.fromDecimals(null, null, BigDecimal.TEN);
         Table expected = new Table(expectedFromInts, expectedFromStrings, expectedFromDecimals)) {
      assertTablesAreEqual(expected, filtered);
    }
  }

  private Table getExpectedFileTable() {
    return getExpectedFileTable(false, false);
  }

  private Table getExpectedFileTable(boolean withNestedColumns) {
    return getExpectedFileTable(true, true);
  }

  private Table getExpectedFileTable(boolean withStructColumns, boolean withListColumn) {
    TestBuilder tb = new TestBuilder()
        .column(true, false, false, true, false)
        .column(5, 1, 0, 2, 7)
        .column(new Byte[]{2, 3, 4, 5, 9})
        .column(3l, 9l, 4l, 2l, 20l)
        .column("this", "is", "a", "test", "string")
        .column(1.0f, 3.5f, 5.9f, 7.1f, 9.8f)
        .column(5.0d, 9.5d, 0.9d, 7.23d, 2.8d);
    StructType nestedType = new StructType(true,
        new BasicType(false, DType.INT32), new BasicType(false, DType.STRING));
    if (withStructColumns) {
      tb.column(nestedType,
          struct(1, "k1"), struct(2, "k2"), struct(3, "k3"),
          struct(4, "k4"), new HostColumnVector.StructData((List) null));
    }
    if (withListColumn) {
      tb.column(new ListType(false, new BasicType(false, DType.INT32)),
          Arrays.asList(1, 2),
          Arrays.asList(3, 4),
          Arrays.asList(5),
          Arrays.asList(6, 7),
          Arrays.asList(8, 9, 10))
          .column(new ListType(false, nestedType),
              Arrays.asList(struct(1, "k1"), struct(2, "k2"), struct(3, "k3")),
              Arrays.asList(struct(4, "k4"), struct(5, "k5")),
              Arrays.asList(struct(6, "k6")),
              Arrays.asList(new HostColumnVector.StructData((List) null)),
              Arrays.asList());
    }
    return tb.build();
  }

  private Table getExpectedFileTableWithDecimals() {
    return new TestBuilder()
        .column(true, false, false, true, false)
        .column(5, 1, 0, 2, 7)
        .column(new Byte[]{2, 3, 4, 5, 9})
        .column(3l, 9l, 4l, 2l, 20l)
        .column("this", "is", "a", "test", "string")
        .column(1.0f, 3.5f, 5.9f, 7.1f, 9.8f)
        .column(5.0d, 9.5d, 0.9d, 7.23d, 2.8d)
        .decimal32Column(3, 298, 2473, 2119, 1273, 9879)
        .decimal64Column(4, 398l, 1322l, 983237l, 99872l, 21337l)
        .build();
  }

  private final class MyBufferConsumer implements HostBufferConsumer, AutoCloseable {
    public final HostMemoryBuffer buffer;
    long offset = 0;

    public MyBufferConsumer() {
      buffer = HostMemoryBuffer.allocate(10 * 1024 * 1024);
    }

    @Override
    public void handleBuffer(HostMemoryBuffer src, long len) {
      try {
        this.buffer.copyFromHostBuffer(offset, src, 0, len);
        offset += len;
      } finally {
        src.close();
      }
    }

    @Override
    public void close() {
      buffer.close();
    }
  }

  private final class MyBufferProvider implements HostBufferProvider {
    private final MyBufferConsumer wrapped;
    long offset = 0;

    private MyBufferProvider(MyBufferConsumer wrapped) {
      this.wrapped = wrapped;
    }

    @Override
    public long readInto(HostMemoryBuffer buffer, long len) {
      long amountLeft = wrapped.offset - offset;
      long amountToCopy = Math.max(0, Math.min(len, amountLeft));
      if (amountToCopy > 0) {
        buffer.copyFromHostBuffer(0, wrapped.buffer, offset, amountToCopy);
        offset += amountToCopy;
      }
      return amountToCopy;
    }
  }

  @Test
  void testParquetWriteToBufferChunkedInt96() {
    try (Table table0 = getExpectedFileTableWithDecimals();
         MyBufferConsumer consumer = new MyBufferConsumer()) {
      ParquetWriterOptions options = ParquetWriterOptions.builder()
          .withNonNullableColumns("_c0", "_c1", "_c2", "_c3", "_c4", "_c5", "_c6")
          .withDecimalColumn("_c7", 5)
          .withDecimalColumn("_c8", 5)
          .build();

      try (TableWriter writer = Table.writeParquetChunked(options, consumer)) {
        writer.write(table0);
        writer.write(table0);
        writer.write(table0);
      }
      try (Table table1 = Table.readParquet(ParquetOptions.DEFAULT, consumer.buffer, 0, consumer.offset);
           Table concat = Table.concatenate(table0, table0, table0)) {
        assertTablesAreEqual(concat, table1);
      }
    }
  }

  @Test
  void testParquetWriteMap() throws IOException {
    ParquetWriterOptions options = ParquetWriterOptions.builder()
        .withMapColumn(mapColumn("my_map",
            new ParquetColumnWriterOptions("key0", false),
            new ParquetColumnWriterOptions("value0"))).build();
    File f = File.createTempFile("test-map", ".parquet");
    List<HostColumnVector.StructData> list1 =
        Arrays.asList(new HostColumnVector.StructData(Arrays.asList("a", "b")));
    List<HostColumnVector.StructData> list2 =
        Arrays.asList(new HostColumnVector.StructData(Arrays.asList("a", "c")));
    List<HostColumnVector.StructData> list3 =
     Arrays.asList(new HostColumnVector.StructData(Arrays.asList("e", "d")));
    HostColumnVector.StructType structType = new HostColumnVector.StructType(true,
     Arrays.asList(new HostColumnVector.BasicType(true, DType.STRING),
        new HostColumnVector.BasicType(true, DType.STRING)));
    try (ColumnVector listColumn = ColumnVector.fromLists(new HostColumnVector.ListType(true,
            structType), list1, list2, list3);
         Table t0 = new Table(listColumn)) {
      try (TableWriter writer = Table.writeParquetChunked(options, f)) {
        writer.write(t0);
      }
      ParquetFileReader reader =
       ParquetFileReader.open(HadoopInputFile.fromPath(new Path(f.getAbsolutePath()),
           new Configuration()));
      MessageType schema = reader.getFooter().getFileMetaData().getSchema();
      assertEquals(OriginalType.MAP, schema.getType("my_map").getOriginalType());
    }
    try (ColumnVector cv = Table.readParquet(f).getColumn(0);
         ColumnVector res = cv.getMapValue(Scalar.fromString("a"));
         ColumnVector expected = ColumnVector.fromStrings("b", "c", null)) {
      assertColumnsAreEqual(expected, res);
    }
  }

  @Test
  void testParquetWriteToBufferChunkedWithNested() {
    ParquetWriterOptions options = ParquetWriterOptions.builder()
        .withNullableColumns("_c0", "_c1", "_c2", "_c3", "_c4", "_c5", "_c6")
        .withStructColumn(structBuilder("_c7")
            .withNullableColumns("_c7-1")
            .withNullableColumns("_c7-2")
            .build())
      .withListColumn(listBuilder("_c8")
            .withNullableColumns("c8-1").build())
        .withListColumn(listBuilder("c9")
            .withStructColumn(structBuilder("c9-1")
                .withNullableColumns("c9-1-1")
                .withNullableColumns("c9-1-2").build())
            .build())
        .build();
    try (Table table0 = getExpectedFileTable(true);
         MyBufferConsumer consumer = new MyBufferConsumer()) {
      try (TableWriter writer = Table.writeParquetChunked(options, consumer)) {
        writer.write(table0);
        writer.write(table0);
        writer.write(table0);
      }
      try (Table table1 = Table.readParquet(ParquetOptions.DEFAULT, consumer.buffer, 0,
          consumer.offset);
           Table concat = Table.concatenate(table0, table0, table0)) {
        assertTablesAreEqual(concat, table1);
      }
    }
  }

  @Test
  void testParquetWriteToBufferChunked() {
    ParquetWriterOptions options = ParquetWriterOptions.builder()
        .withNullableColumns("_c0", "_c1", "_c2", "_c3", "_c4", "_c5", "_c6")
        .withStructColumn(structBuilder("_c7")
            .withNullableColumns("_c7-1")
            .withNullableColumns("_c7-2")
            .build())
        .build();
    try (Table table0 = getExpectedFileTable(true, false);
         MyBufferConsumer consumer = new MyBufferConsumer()) {
         try (TableWriter writer = Table.writeParquetChunked(options, consumer)) {
           writer.write(table0);
           writer.write(table0);
           writer.write(table0);
         }
      try (Table table1 = Table.readParquet(ParquetOptions.DEFAULT, consumer.buffer, 0, consumer.offset);
           Table concat = Table.concatenate(table0, table0, table0)) {
        assertTablesAreEqual(concat, table1);
      }
    }
  }

  @Test
  void testParquetWriteToFileWithNames() throws IOException {
    File tempFile = File.createTempFile("test-names", ".parquet");
    try (Table table0 = getExpectedFileTableWithDecimals()) {
      ParquetWriterOptions options = ParquetWriterOptions.builder()
          .withNonNullableColumns("first", "second", "third", "fourth", "fifth", "sixth", "seventh")
          .withDecimalColumn("eighth", 5)
          .withDecimalColumn("ninth", 6)
          .withCompressionType(CompressionType.NONE)
          .withStatisticsFrequency(ParquetWriterOptions.StatisticsFrequency.NONE)
          .build();
      try (TableWriter writer = Table.writeParquetChunked(options, tempFile.getAbsoluteFile())) {
        writer.write(table0);
      }
      try (Table table2 = Table.readParquet(tempFile.getAbsoluteFile())) {
        assertTablesAreEqual(table0, table2);
      }
    } finally {
      tempFile.delete();
    }
  }

  @Test
  void testParquetWriteToFileWithNamesAndMetadata() throws IOException {
    File tempFile = File.createTempFile("test-names-metadata", ".parquet");
    try (Table table0 = getExpectedFileTableWithDecimals()) {
      ParquetWriterOptions options = ParquetWriterOptions.builder()
          .withNonNullableColumns("first", "second", "third", "fourth", "fifth", "sixth", "seventh")
          .withDecimalColumn("eighth", 6)
          .withDecimalColumn("ninth", 8)
          .withMetadata("somekey", "somevalue")
          .withCompressionType(CompressionType.NONE)
          .withStatisticsFrequency(ParquetWriterOptions.StatisticsFrequency.NONE)
          .build();
      try (TableWriter writer = Table.writeParquetChunked(options, tempFile.getAbsoluteFile())) {
        writer.write(table0);
      }
      try (Table table2 = Table.readParquet(tempFile.getAbsoluteFile())) {
        assertTablesAreEqual(table0, table2);
      }
    } finally {
      tempFile.delete();
    }
  }

  @Test
  void testParquetWriteToFileUncompressedNoStats() throws IOException {
    File tempFile = File.createTempFile("test-uncompressed", ".parquet");
    try (Table table0 = getExpectedFileTableWithDecimals()) {
      ParquetWriterOptions options = ParquetWriterOptions.builder()
          .withNonNullableColumns("_c0", "_c1", "_c2", "_c3", "_c4", "_c5", "_c6")
          .withDecimalColumn("_c7", 4)
          .withDecimalColumn("_c8", 6)
          .withCompressionType(CompressionType.NONE)
          .withStatisticsFrequency(ParquetWriterOptions.StatisticsFrequency.NONE)
          .build();
      try (TableWriter writer = Table.writeParquetChunked(options, tempFile.getAbsoluteFile())) {
        writer.write(table0);
      }
      try (Table table2 = Table.readParquet(tempFile.getAbsoluteFile())) {
        assertTablesAreEqual(table0, table2);
      }
    } finally {
      tempFile.delete();
    }
  }

  @Test
  void testArrowIPCWriteToFileWithNamesAndMetadata() throws IOException {
    File tempFile = File.createTempFile("test-names-metadata", ".arrow");
    try (Table table0 = getExpectedFileTable()) {
      ArrowIPCWriterOptions options = ArrowIPCWriterOptions.builder()
              .withColumnNames("first", "second", "third", "fourth", "fifth", "sixth", "seventh")
              .build();
      try (TableWriter writer = Table.writeArrowIPCChunked(options, tempFile.getAbsoluteFile())) {
        writer.write(table0);
      }
      try (StreamedTableReader reader = Table.readArrowIPCChunked(tempFile)) {
        boolean done = false;
        int count = 0;
        while (!done) {
          try (Table t = reader.getNextIfAvailable()) {
            if (t == null) {
              done = true;
            } else {
              assertTablesAreEqual(table0, t);
              count++;
            }
          }
        }
        assertEquals(1, count);
      }
    } finally {
      tempFile.delete();
    }
  }

  @Test
  void testArrowIPCWriteToBufferChunked() {
    try (Table table0 = getExpectedFileTable(true);
         MyBufferConsumer consumer = new MyBufferConsumer()) {
      ArrowIPCWriterOptions options = ArrowIPCWriterOptions.builder()
              .withColumnNames("first", "second", "third", "fourth", "fifth", "sixth", "seventh")
              .withColumnNames("eighth", "eighth_id", "eighth_name")
              .withColumnNames("ninth")
              .withColumnNames("tenth", "child_id", "child_name")
              .build();
      try (TableWriter writer = Table.writeArrowIPCChunked(options, consumer)) {
        writer.write(table0);
        writer.write(table0);
        writer.write(table0);
      }
      try (StreamedTableReader reader = Table.readArrowIPCChunked(new MyBufferProvider(consumer))) {
        boolean done = false;
        int count = 0;
        while (!done) {
          try (Table t = reader.getNextIfAvailable()) {
            if (t == null) {
              done = true;
            } else {
              assertTablesAreEqual(table0, t);
              count++;
            }
          }
        }
        assertEquals(3, count);
      }
    }
  }

  @Test
  void testORCWriteToBufferChunked() {
    try (Table table0 = getExpectedFileTable();
         MyBufferConsumer consumer = new MyBufferConsumer()) {
      String[] colNames = new String[table0.getNumberOfColumns()];
      Arrays.fill(colNames, "");
      ORCWriterOptions opts = ORCWriterOptions.builder().withColumnNames(colNames).build();
      try (TableWriter writer = Table.writeORCChunked(opts, consumer)) {
        writer.write(table0);
        writer.write(table0);
        writer.write(table0);
      }
      try (Table table1 = Table.readORC(ORCOptions.DEFAULT, consumer.buffer, 0, consumer.offset);
           Table concat = Table.concatenate(table0, table0, table0)) {
        assertTablesAreEqual(concat, table1);
      }
    }
  }

  @Test
  void testORCWriteToFile() throws IOException {
    File tempFile = File.createTempFile("test", ".orc");
    try (Table table0 = getExpectedFileTable()) {
      table0.writeORC(tempFile.getAbsoluteFile());
      try (Table table1 = Table.readORC(tempFile.getAbsoluteFile())) {
        assertTablesAreEqual(table0, table1);
      }
    } finally {
      tempFile.delete();
    }
  }

  @Test
  void testORCWriteToFileWithColNames() throws IOException {
    File tempFile = File.createTempFile("test", ".orc");
    final String[] colNames = new String[]{"bool", "int", "byte","long","str","float","double"};
    try (Table table0 = getExpectedFileTable()) {
      ORCWriterOptions options = ORCWriterOptions.builder()
          .withColumnNames(colNames)
          .withMetadata("somekey", "somevalue")
          .build();
      table0.writeORC(options, tempFile.getAbsoluteFile());
      ORCOptions opts = ORCOptions.builder().includeColumn(colNames).build();
      try (Table table1 = Table.readORC(opts, tempFile.getAbsoluteFile())) {
        assertTablesAreEqual(table0, table1);
      }
    } finally {
      tempFile.delete();
    }
  }

  @Test
  void testORCWriteToFileUncompressed() throws IOException {
    File tempFileUncompressed = File.createTempFile("test-uncompressed", ".orc");
    try (Table table0 = getExpectedFileTable()) {
      String[] colNames = new String[table0.getNumberOfColumns()];
      Arrays.fill(colNames, "");
      ORCWriterOptions opts = ORCWriterOptions.builder()
              .withColumnNames(colNames)
              .withCompressionType(CompressionType.NONE)
              .build();
      table0.writeORC(opts, tempFileUncompressed.getAbsoluteFile());
      try (Table table2 = Table.readORC(tempFileUncompressed.getAbsoluteFile())) {
        assertTablesAreEqual(table0, table2);
      }
    } finally {
      tempFileUncompressed.delete();
    }
  }

  @Test
  void testStructColumnFilter() {
    List<HostColumnVector.DataType> children =
        Arrays.asList(new HostColumnVector.BasicType(true, DType.INT32),
            new HostColumnVector.BasicType(true, DType.INT64));
    HostColumnVector.StructType type = new HostColumnVector.StructType(true, children);
    HostColumnVector.StructType expectedType = new HostColumnVector.StructType(true, children);
    List data1 = Arrays.asList(10, 20L);
    List data2 = Arrays.asList(50, 60L);
    List data3 = Arrays.asList(null, 80L);
    List data4 = null;
    HostColumnVector.StructData structData1 = new HostColumnVector.StructData(data1);
    HostColumnVector.StructData structData2 = new HostColumnVector.StructData(data2);
    HostColumnVector.StructData structData3 = new HostColumnVector.StructData(data3);
    HostColumnVector.StructData structData4 = new HostColumnVector.StructData(data4);
    try (ColumnVector mask = ColumnVector.fromBoxedBooleans(true, false, true, false);
         ColumnVector fromStructs = ColumnVector.fromStructs(type, Arrays.asList(structData1, structData2, structData3, structData4));
         Table input = new Table(fromStructs);
         Table filteredTable = input.filter(mask);
         ColumnVector expectedStructs = ColumnVector.fromStructs(expectedType, Arrays.asList(structData1, structData3));
         Table expected = new Table(expectedStructs)) {
      assertTablesAreEqual(expected, filteredTable);
    }
  }

  @Test
  void testStructColumnFilterStrings() {
    List<HostColumnVector.DataType> children =
        Arrays.asList(new HostColumnVector.BasicType(true, DType.STRING),
            new HostColumnVector.BasicType(true, DType.STRING));
    HostColumnVector.StructType type = new HostColumnVector.StructType(true, children);
    HostColumnVector.StructType expectedType = new HostColumnVector.StructType(true, children);
    List data1 = Arrays.asList("10", "aliceAndBob");
    List data2 = Arrays.asList("50", "foobar");
    List data3 = Arrays.asList(null, "zombies");
    List data4 = null;
    HostColumnVector.StructData structData1 = new HostColumnVector.StructData(data1);
    HostColumnVector.StructData structData2 = new HostColumnVector.StructData(data2);
    HostColumnVector.StructData structData3 = new HostColumnVector.StructData(data3);
    HostColumnVector.StructData structData4 = new HostColumnVector.StructData(data4);
    try (ColumnVector mask = ColumnVector.fromBoxedBooleans(true, false, true, true);
         ColumnVector fromStructs = ColumnVector.fromStructs(type, Arrays.asList(structData1, structData2, structData3, structData4));
         Table input = new Table(fromStructs);
         Table filteredTable = input.filter(mask);
         ColumnVector expectedStructs = ColumnVector.fromStructs(expectedType, Arrays.asList(structData1, structData3, structData4));
         Table expected = new Table(expectedStructs)) {
      assertEquals(expected.getRowCount(), 3L, "Expected column row count is incorrect");
      assertTablesAreEqual(expected, filteredTable);
    }
  }

  @Test
  void fixedWidthRowsRoundTrip() {
    try (Table t = new TestBuilder()
        .column(3l, 9l, 4l, 2l, 20l, null)
        .column(5.0d, 9.5d, 0.9d, 7.23d, 2.8d, null)
        .column(5, 1, 0, 2, 7, null)
        .column(true, false, false, true, false, null)
        .column(1.0f, 3.5f, 5.9f, 7.1f, 9.8f, null)
        .column(new Byte[]{2, 3, 4, 5, 9, null})
        .decimal32Column(-3, RoundingMode.UNNECESSARY, 5.0d, 9.5d, 0.9d, 7.23d, 2.8d, null)
        .decimal64Column(-8, 3L, 9L, 4L, 2L, 20L, null)
        .build()) {
      ColumnVector[] rows = t.convertToRows();
      try {
        // We didn't overflow
        assert rows.length == 1;
        ColumnVector cv = rows[0];
        assert cv.getRowCount() == t.getRowCount();
//        try (HostColumnVector hcv = cv.copyToHost()) {
//          hcv.getChildColumnView(0).getDataBuffer().printBuffer(8);
//        }

        DType[] types = new DType[t.getNumberOfColumns()];
        for (int i = 0; i < t.getNumberOfColumns(); i++) {
          types[i] = t.getColumn(i).getType();
        }
        try (Table backAgain = Table.convertFromRows(cv, types)) {
          assertTablesAreEqual(t, backAgain);
        }
      } finally {
        for (ColumnVector cv : rows) {
          cv.close();
        }
      }
    }
  }

  // utility methods to reduce typing

  private StructData struct(Object... values) {
    return new StructData(values);
  }

  private StructData[] structs(StructData... values) {
    return values;
  }

  private String[] strings(String... values) {
    return values;
  }

  private static ColumnVector decimalFromBoxedInts(boolean isDec64, int scale, Integer... values) {
    BigDecimal[] decimals = new BigDecimal[values.length];
    for (int i = 0; i < values.length; i++) {
      if (values[i] == null) {
        decimals[i] = null;
      } else {
        decimals[i] = BigDecimal.valueOf(values[i], -scale);
      }
    }
    DType type = isDec64 ? DType.create(DType.DTypeEnum.DECIMAL64, scale) : DType.create(DType.DTypeEnum.DECIMAL32, scale);
    return ColumnVector.build(type, decimals.length, (b) -> b.appendBoxed(decimals));
  }

  private Table buildTestTable() {
    StructType mapStructType = new StructType(true,
        new BasicType(false, DType.STRING),
        new BasicType(false, DType.STRING));
    StructType structType = new StructType(true,
        new BasicType(true, DType.INT32),
        new BasicType(false, DType.FLOAT32));
    return new Table.TestBuilder()
        .column(     100,      202,      3003,    40004,        5,      -60,    1, null,    3,  null,     5, null,    7, null,   9,   null,    11, null,   13, null,  15)
        .column(    true,     true,     false,    false,     true,     null, true, true, null, false, false, null, true, true, null, false, false, null, true, true, null)
        .column( (byte)1,  (byte)2,      null,  (byte)4,  (byte)5,  (byte)6, (byte)1, (byte)2, (byte)3, null, (byte)5, (byte)6, (byte)7, null, (byte)9, (byte)10, (byte)11, null, (byte)13, (byte)14, (byte)15)
        .column((short)6, (short)5,  (short)4,     null, (short)2, (short)1, (short)1, (short)2, (short)3, null, (short)5, (short)6, (short)7, null, (short)9, (short)10, null, (short)12, (short)13, (short)14, null)
        .column(      1L,     null,     1001L,      50L,   -2000L,     null, 1L, 2L, 3L, 4L, null, 6L, 7L, 8L, 9L, null, 11L, 12L, 13L, 14L, null)
        .column(   10.1f,      20f, Float.NaN,  3.1415f,     -60f,     null, 1f, 2f, 3f, 4f, 5f, null, 7f, 8f, 9f, 10f, 11f, null, 13f, 14f, 15f)
        .column(   10.1f,      20f, Float.NaN,  3.1415f,     -60f,     -50f, 1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f, 11f, 12f, 13f, 14f, 15f)
        .column(    10.1,     20.0,      33.1,   3.1415,    -60.5,     null, 1., 2., 3., 4., 5., 6., null, 8., 9., 10., 11., 12., null, 14., 15.)
        .timestampDayColumn(99,      100,      101,      102,      103,      104, 1, 2, 3, 4, 5, 6, 7, null, 9, 10, 11, 12, 13, null, 15)
        .timestampMillisecondsColumn(9L,    1006L,     101L,    5092L,     null,      88L, 1L, 2L, 3L, 4L, 5L ,6L, 7L, 8L, null, 10L, 11L, 12L, 13L, 14L, 15L)
        .timestampSecondsColumn(1L, null, 3L, 4L, 5L, 6L, 1L, 2L, 3L, 4L, 5L ,6L, 7L, 8L, 9L, null, 11L, 12L, 13L, 14L, 15L)
        .decimal32Column(-3, 100,      202,      3003,    40004,        5,      -60,    1, null,    3,  null,     5, null,    7, null,   9,   null,    11, null,   13, null,  15)
        .decimal64Column(-8,      1L,     null,     1001L,      50L,   -2000L,     null, 1L, 2L, 3L, 4L, null, 6L, 7L, 8L, 9L, null, 11L, 12L, 13L, 14L, null)
        .column(     "A",      "B",      "C",      "D",     null,   "TESTING", "1", "2", "3", "4", "5", "6", "7", null, "9", "10", "11", "12", "13", null, "15")
        .column(
            strings("1", "2", "3"), strings("4"), strings("5"), strings("6, 7"),
            strings("", "9", null), strings("11"), strings(""), strings(null, null),
            strings("15", null), null, null, strings("18", "19", "20"),
            null, strings("22"), strings("23", ""), null,
            null, null, null, strings(),
            strings("the end"))
        .column(mapStructType,
            structs(struct("1", "2")), structs(struct("3", "4")),
            null, null,
            structs(struct("key", "value"), struct("a", "b")), null,
            null, structs(struct("3", "4"), struct("1", "2")),
            structs(), structs(null, struct("foo", "bar")),
            structs(null, null, null), null,
            null, null,
            null, null,
            null, null,
            null, null,
            structs(struct("the", "end")))
        .column(structType,
            struct(1, 1f), null, struct(2, 3f), null, struct(8, 7f),
            struct(0, 0f), null, null, struct(-1, -1f), struct(-100, -100f),
            struct(Integer.MAX_VALUE, Float.MAX_VALUE), null, null, null, null,
            null, null, null, null, null,
            struct(Integer.MIN_VALUE, Float.MIN_VALUE))
        .column(     "A",      "A",      "C",      "C",     null,   "TESTING", "1", "2", "3", "4", "5", "6", "7", null, "9", "10", "11", "12", "13", null, "15")
        .build();
  }

  @Test
  void testBuilderWithColumn() {
    try (Table t1 = new Table.TestBuilder()
        .decimal32Column(-3, 120, -230, null, 340)
        .decimal64Column(-8, 1000L, 200L, null, 30L).build()) {
      try (Table t2 = new Table.TestBuilder()
          .decimal32Column(-3, RoundingMode.UNNECESSARY, 0.12, -0.23, null, 0.34)
          .decimal64Column(-8, RoundingMode.UNNECESSARY, 1e-5, 2e-6, null, 3e-7).build()) {
        try (Table t3 = new Table.TestBuilder()
            .decimal32Column(-3, RoundingMode.UNNECESSARY, "0.12", "-000.23", null, ".34")
            .decimal64Column(-8, RoundingMode.UNNECESSARY, "1e-5", "2e-6", null, "3e-7").build()) {
          assertTablesAreEqual(t1, t2);
          assertTablesAreEqual(t1, t3);
        }
      }
    }
  }

  private Table[] buildExplodeTestTableWithPrimitiveTypes(boolean pos, boolean outer) {
    try (Table input = new Table.TestBuilder()
        .column(new ListType(true, new BasicType(true, DType.INT32)),
            Arrays.asList(1, 2, 3),
            Arrays.asList(4, 5),
            Arrays.asList(6),
            null,
            Arrays.asList())
        .column("s1", "s2", "s3", "s4", "s5")
        .column(1, 3, 5, 7, 9)
        .column(12.0, 14.0, 13.0, 11.0, 15.0)
        .build()) {
      Table.TestBuilder expectedBuilder = new Table.TestBuilder();
      if (pos) {
        Integer[] posData = outer ? new Integer[]{0, 1, 2, 0, 1, 0, null, null} : new Integer[]{0, 1, 2, 0, 1, 0};
        expectedBuilder.column(posData);
      }
      List<Object[]> expectedData = new ArrayList<Object[]>(){{
        if (!outer) {
          this.add(new Integer[]{1, 2, 3, 4, 5, 6});
          this.add(new String[]{"s1", "s1", "s1", "s2", "s2", "s3"});
          this.add(new Integer[]{1, 1, 1, 3, 3, 5});
          this.add(new Double[]{12.0, 12.0, 12.0, 14.0, 14.0, 13.0});
        } else {
          this.add(new Integer[]{1, 2, 3, 4, 5, 6, null, null});
          this.add(new String[]{"s1", "s1", "s1", "s2", "s2", "s3", "s4", "s5"});
          this.add(new Integer[]{1, 1, 1, 3, 3, 5, 7, 9});
          this.add(new Double[]{12.0, 12.0, 12.0, 14.0, 14.0, 13.0, 11.0, 15.0});
        }
      }};
      try (Table expected = expectedBuilder.column((Integer[]) expectedData.get(0))
          .column((String[]) expectedData.get(1))
          .column((Integer[]) expectedData.get(2))
          .column((Double[]) expectedData.get(3))
          .build()) {
        return new Table[]{new Table(input.getColumns()), new Table(expected.getColumns())};
      }
    }
  }

  private Table[] buildExplodeTestTableWithNestedTypes(boolean pos, boolean outer) {
    StructType nestedType = new StructType(true,
        new BasicType(false, DType.INT32), new BasicType(false, DType.STRING));
    try (Table input = new Table.TestBuilder()
        .column(new ListType(false, nestedType),
            Arrays.asList(struct(1, "k1"), struct(2, "k2"), struct(3, "k3")),
            Arrays.asList(struct(4, "k4"), struct(5, "k5")),
            Arrays.asList(struct(6, "k6")),
            Arrays.asList(new HostColumnVector.StructData((List) null)),
            null)
        .column("s1", "s2", "s3", "s4", "s5")
        .column(1, 3, 5, 7, 9)
        .column(12.0, 14.0, 13.0, 11.0, 15.0)
        .build()) {
      Table.TestBuilder expectedBuilder = new Table.TestBuilder();
      if (pos) {
        if (outer) {
          expectedBuilder.column(0, 1, 2, 0, 1, 0, 0, null);
        } else {
          expectedBuilder.column(0, 1, 2, 0, 1, 0, 0);
        }
      }
      List<Object[]> expectedData = new ArrayList<Object[]>(){{
        if (!outer) {
          this.add(new HostColumnVector.StructData[]{
              struct(1, "k1"), struct(2, "k2"), struct(3, "k3"),
              struct(4, "k4"), struct(5, "k5"), struct(6, "k6"),
              new HostColumnVector.StructData((List) null)});
          this.add(new String[]{"s1", "s1", "s1", "s2", "s2", "s3", "s4"});
          this.add(new Integer[]{1, 1, 1, 3, 3, 5, 7});
          this.add(new Double[]{12.0, 12.0, 12.0, 14.0, 14.0, 13.0, 11.0});
        } else {
          this.add(new HostColumnVector.StructData[]{
              struct(1, "k1"), struct(2, "k2"), struct(3, "k3"),
              struct(4, "k4"), struct(5, "k5"), struct(6, "k6"),
              new HostColumnVector.StructData((List) null), null});
          this.add(new String[]{"s1", "s1", "s1", "s2", "s2", "s3", "s4", "s5"});
          this.add(new Integer[]{1, 1, 1, 3, 3, 5, 7, 9});
          this.add(new Double[]{12.0, 12.0, 12.0, 14.0, 14.0, 13.0, 11.0, 15.0});
        }
      }};
      try (Table expected = expectedBuilder
          .column(nestedType, (HostColumnVector.StructData[]) expectedData.get(0))
          .column((String[]) expectedData.get(1))
          .column((Integer[]) expectedData.get(2))
          .column((Double[]) expectedData.get(3))
          .build()) {
        return new Table[]{new Table(input.getColumns()), new Table(expected.getColumns())};
      }
    }
  }

  @Test
  void testExplode() {
    // Child is primitive type
    Table[] testTables = buildExplodeTestTableWithPrimitiveTypes(false, false);
    try (Table input = testTables[0];
         Table expected = testTables[1]) {
      try (Table exploded = input.explode(0)) {
        assertTablesAreEqual(expected, exploded);
      }
    }

    // Child is nested type
    Table[] testTables2 = buildExplodeTestTableWithNestedTypes(false, false);
    try (Table input = testTables2[0];
         Table expected = testTables2[1]) {
      try (Table exploded = input.explode(0)) {
        assertTablesAreEqual(expected, exploded);
      }
    }
  }

  @Test
  void testExplodePosition() {
    // Child is primitive type
    Table[] testTables = buildExplodeTestTableWithPrimitiveTypes(true, false);
    try (Table input = testTables[0];
         Table expected = testTables[1]) {
      try (Table exploded = input.explodePosition(0)) {
        assertTablesAreEqual(expected, exploded);
      }
    }

    // Child is nested type
    Table[] testTables2 = buildExplodeTestTableWithNestedTypes(true, false);
    try (Table input = testTables2[0];
         Table expected = testTables2[1]) {
      try (Table exploded = input.explodePosition(0)) {
        assertTablesAreEqual(expected, exploded);
      }
    }
  }

  @Test
  void testExplodeOuter() {
    // Child is primitive type
    Table[] testTables = buildExplodeTestTableWithPrimitiveTypes(false, true);
    try (Table input = testTables[0];
         Table expected = testTables[1]) {
      try (Table exploded = input.explodeOuter(0)) {
        assertTablesAreEqual(expected, exploded);
      }
    }

    // Child is nested type
    Table[] testTables2 = buildExplodeTestTableWithNestedTypes(false, true);
    try (Table input = testTables2[0];
         Table expected = testTables2[1]) {
      try (Table exploded = input.explodeOuter(0)) {
        assertTablesAreEqual(expected, exploded);
      }
    }
  }

  @Test
  void testExplodeOuterPosition() {
    // Child is primitive type
    Table[] testTables = buildExplodeTestTableWithPrimitiveTypes(true, true);
    try (Table input = testTables[0];
         Table expected = testTables[1]) {
      try (Table exploded = input.explodeOuterPosition(0)) {
        assertTablesAreEqual(expected, exploded);
      }
    }

    // Child is nested type
    Table[] testTables2 = buildExplodeTestTableWithNestedTypes(true, true);
    try (Table input = testTables2[0];
         Table expected = testTables2[1]) {
      try (Table exploded = input.explodeOuterPosition(0)) {
        assertTablesAreEqual(expected, exploded);
      }
    }
  }
}
