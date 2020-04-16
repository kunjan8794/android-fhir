package com.google.fhirengine.db.impl;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.provider.BaseColumns;

import com.google.common.base.Joiner;
import com.google.fhirengine.db.Database;
import com.google.fhirengine.db.ResourceAlreadyExistsInDbException;
import com.google.fhirengine.db.ResourceNotFoundInDbException;
import com.google.fhirengine.index.FhirIndexer;
import com.google.fhirengine.index.ResourceIndices;
import com.google.fhirengine.index.StringIndex;
import com.google.fhirengine.resource.ResourceUtils;

import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ResourceType;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import ca.uhn.fhir.parser.IParser;

/** Helper class that manages the FHIR resource database, and provides a database connection. */
public class DatabaseImpl extends SQLiteOpenHelper implements Database {

  private static String DB_NAME = "FHIRDB";
  private static int DB_VERSION = 1;

  /** Table names */
  interface Tables {
    String RESOURCES = "resources";
    String STRING_INDICES = "string_indices";
  }

  /** {@link Tables#RESOURCES} columns. */
  interface ResourcesColumns extends BaseColumns {
    String RESOURCE_TYPE = "resource_type";
    String RESOURCE_ID = "resource_id";
    String RESOURCE = "resource";
  }

  /** {@link Tables#STRING_INDICES} columns. */
  interface StringIndicesColumns extends BaseColumns {
    String RESOURCE_TYPE = "resource_type";
    String INDEX_NAME = "index_name";
    String INDEX_PATH = "index_path";
    String INDEX_VALUE = "index_value";
    String RESOURCE_ID = "resource_id";
  }

  /** Unique indices */
  private interface UniqueIndices {
    String RESOURCE_TYPE_RESOURCE_ID_UNIQUE_INDEX =
        Joiner.on("_")
            .join(Tables.RESOURCES, ResourcesColumns.RESOURCE_TYPE, ResourcesColumns.RESOURCE_ID);

  }

  /** Indices */
  private interface Indices {
    String STRING_INDICES_TABLE_RESOURCE_TYPE_INDEX_NAME_INDEX_VALUE_INDEX =
        Joiner.on("_")
            .join(Tables.STRING_INDICES, StringIndicesColumns.RESOURCE_TYPE,
                StringIndicesColumns.INDEX_NAME,
                StringIndicesColumns.INDEX_VALUE);
  }

  private static String CREATE_RESOURCES_TABLE =
      "CREATE TABLE " + Tables.RESOURCES + " ( " +
          ResourcesColumns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
          ResourcesColumns.RESOURCE_TYPE + " TEXT NOT NULL," +
          ResourcesColumns.RESOURCE_ID + " TEXT NOT NULL," +
          ResourcesColumns.RESOURCE + " TEXT NOT NULL);";
  private static String CREATE_RESOURCE_TABLE_UNIQUE_INDEX =
      "CREATE UNIQUE INDEX " + UniqueIndices.RESOURCE_TYPE_RESOURCE_ID_UNIQUE_INDEX + " ON " +
          Tables.RESOURCES + " ( " +
          ResourcesColumns.RESOURCE_TYPE + ", " +
          ResourcesColumns.RESOURCE_ID + ");";

  private static String CREATE_STRING_INDICES_TABLE =
      "CREATE TABLE " + Tables.STRING_INDICES + " ( " +
          StringIndicesColumns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
          StringIndicesColumns.RESOURCE_TYPE + " TEXT NOT NULL," +
          StringIndicesColumns.INDEX_NAME + " TEXT NOT NULL," +
          StringIndicesColumns.INDEX_PATH + " TEXT NOT NULL," +
          StringIndicesColumns.INDEX_VALUE + " TEXT NOT NULL," +
          StringIndicesColumns.RESOURCE_ID + " TEXT NOT NULL);";
  private static String CREATE_STRING_INDICES_TABLE_INDEX =
      "CREATE INDEX " + Indices.STRING_INDICES_TABLE_RESOURCE_TYPE_INDEX_NAME_INDEX_VALUE_INDEX +
          " ON " +
          Tables.STRING_INDICES + " ( " +
          StringIndicesColumns.RESOURCE_TYPE + ", " +
          StringIndicesColumns.INDEX_NAME + ", " +
          StringIndicesColumns.INDEX_VALUE + ");";

  private final IParser iParser;
  private final FhirIndexer fhirIndexer;

  @Inject
  DatabaseImpl(Context context, IParser iParser, FhirIndexer fhirIndexer) {
    super(context, DB_NAME, null, DB_VERSION);
    this.iParser = iParser;
    this.fhirIndexer = fhirIndexer;
  }

  @Override
  public void onCreate(SQLiteDatabase sqLiteDatabase) {
    sqLiteDatabase.execSQL(CREATE_RESOURCES_TABLE);
    sqLiteDatabase.execSQL(CREATE_STRING_INDICES_TABLE);
    sqLiteDatabase.execSQL(CREATE_RESOURCE_TABLE_UNIQUE_INDEX);
    sqLiteDatabase.execSQL(CREATE_STRING_INDICES_TABLE_INDEX);
  }

  @Override
  public void onUpgrade(SQLiteDatabase sqLiteDatabase, int i, int i1) {
    throw new UnsupportedOperationException("Not implemented yet!");
  }

  @Override
  public <R extends Resource> void insert(R resource) throws ResourceAlreadyExistsInDbException {
    String type = resource.getResourceType().name();
    String id = resource.getId();
    ContentValues contentValues = new ContentValues();
    contentValues.put(ResourcesColumns.RESOURCE_TYPE, type);
    contentValues.put(ResourcesColumns.RESOURCE_ID, id);
    contentValues.put(ResourcesColumns.RESOURCE, iParser.encodeResourceToString(resource));
    SQLiteDatabase database = getWritableDatabase();
    try {
      database.beginTransaction();

      // Insert resource itself.
      database.insertOrThrow(Tables.RESOURCES, null, contentValues);

      // Insert string indices.
      ResourceIndices resourceIndices = fhirIndexer.index(resource);
      for (StringIndex stringIndex : resourceIndices.getStringIndices()) {
        ContentValues stringIndexContentValues = new ContentValues();
        stringIndexContentValues.put(StringIndicesColumns.RESOURCE_TYPE, type);
        stringIndexContentValues.put(StringIndicesColumns.INDEX_NAME, stringIndex.name());
        stringIndexContentValues.put(StringIndicesColumns.INDEX_PATH, stringIndex.path());
        stringIndexContentValues.put(StringIndicesColumns.INDEX_VALUE, stringIndex.value());
        stringIndexContentValues.put(StringIndicesColumns.RESOURCE_ID, id);
        database.replaceOrThrow(Tables.STRING_INDICES, null, stringIndexContentValues);
      }

      database.setTransactionSuccessful();
      database.endTransaction();
    } catch (SQLiteConstraintException e) {
      throw new ResourceAlreadyExistsInDbException(type, id, e);
    } finally {
      database.close();
    }
  }

  @Override
  public <R extends Resource> void update(R resource) {
    String type = resource.getResourceType().name();
    String id = resource.getId();
    ContentValues contentValues = new ContentValues();
    contentValues.put(ResourcesColumns.RESOURCE_TYPE, type);
    contentValues.put(ResourcesColumns.RESOURCE_ID, id);
    contentValues.put(ResourcesColumns.RESOURCE, iParser.encodeResourceToString(resource));
    SQLiteDatabase database = getWritableDatabase();
    try {
      database.replaceOrThrow(Tables.RESOURCES, null, contentValues);
    } finally {
      database.close();
    }
  }

  @Override
  public <R extends Resource> R select(Class<R> clazz, String id)
      throws ResourceNotFoundInDbException {
    String type = ResourceUtils.getResourceType(clazz).name();

    String[] columns = new String[]{ResourcesColumns.RESOURCE};
    String whereClause =
        ResourcesColumns.RESOURCE_TYPE + " = ? AND " + ResourcesColumns.RESOURCE_ID + " = ?";
    String[] whereArgs = new String[]{type, id};
    SQLiteDatabase database = getReadableDatabase();
    Cursor cursor = database
        .query(Tables.RESOURCES, columns, whereClause, whereArgs, null, null, null);
    try {
      if (cursor == null) {
        throw new SQLException("Null cursor!");
      }
      if (cursor.getCount() == 0) {
        throw new ResourceNotFoundInDbException(type, id);
      }
      if (cursor.getCount() > 1) {
        throw new SQLException("Unexpected number of records!");
      }
      cursor.moveToFirst();
      return iParser.parseResource(clazz, cursor.getString(0));
    } finally {
      cursor.close();
      database.close();
    }
  }

  @Override
  public <R extends Resource> void delete(Class<R> clazz, String id) {
    throw new UnsupportedOperationException("Not implemented yet!");
  }
}
