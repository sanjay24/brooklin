package com.linkedin.datastream.testutil.event.generator;

import java.io.File;
import java.io.IOException;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.log4j.Logger;


public class RandomGenericRecordGenerator {
  public final static String MODULE = RandomGenericRecordGenerator.class.getName();
  public final static Logger LOG = Logger.getLogger(MODULE);
  Schema schema;

  /*
   * Takes a schema file as input
   */
  public RandomGenericRecordGenerator(File schemaFile) throws IOException {
    schema = Schema.parse(schemaFile);
  }

  /*
   * Takes a schema string as an input
   */
  public RandomGenericRecordGenerator(String schema) {
    this.schema = Schema.parse(schema);
  }

  /*
   * Generate random based on the avro schema
   * The schema must be of a record type to work
   *
   * @return returns the randomly generated record
   */
  public GenericRecord generateRandomRecord() throws UnknownTypeException {

    if (schema.getType() != Schema.Type.RECORD) {
      LOG.error("The schema first level must be record.");
      return null;
    }

    GenericRecord record = new GenericData.Record(schema);
    for (Schema.Field field : schema.getFields()) {
      SchemaField schemaFill = SchemaField.createField(field);
      schemaFill.writeToRecord(record);
    }
    return record;
  }
}