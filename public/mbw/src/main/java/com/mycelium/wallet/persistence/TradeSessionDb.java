/*
 * Copyright 2013 Megion Research and Development GmbH
 *
 * Licensed under the Microsoft Reference Source License (MS-RSL)
 *
 * This license governs use of the accompanying software. If you use the software, you accept this license.
 * If you do not accept the license, do not use the software.
 *
 * 1. Definitions
 * The terms "reproduce," "reproduction," and "distribution" have the same meaning here as under U.S. copyright law.
 * "You" means the licensee of the software.
 * "Your company" means the company you worked for when you downloaded the software.
 * "Reference use" means use of the software within your company as a reference, in read only form, for the sole purposes
 * of debugging your products, maintaining your products, or enhancing the interoperability of your products with the
 * software, and specifically excludes the right to distribute the software outside of your company.
 * "Licensed patents" means any Licensor patent claims which read directly on the software as distributed by the Licensor
 * under this license.
 *
 * 2. Grant of Rights
 * (A) Copyright Grant- Subject to the terms of this license, the Licensor grants you a non-transferable, non-exclusive,
 * worldwide, royalty-free copyright license to reproduce the software for reference use.
 * (B) Patent Grant- Subject to the terms of this license, the Licensor grants you a non-transferable, non-exclusive,
 * worldwide, royalty-free patent license under licensed patents for reference use.
 *
 * 3. Limitations
 * (A) No Trademark License- This license does not grant you any rights to use the Licensor’s name, logo, or trademarks.
 * (B) If you begin patent litigation against the Licensor over patents that you think may apply to the software
 * (including a cross-claim or counterclaim in a lawsuit), your license to the software ends automatically.
 * (C) The software is licensed "as-is." You bear the risk of using it. The Licensor gives no express warranties,
 * guarantees or conditions. You may have additional consumer rights under your local laws which this license cannot
 * change. To the extent permitted under your local laws, the Licensor excludes the implied warranties of merchantability,
 * fitness for a particular purpose and non-infringement.
 */

package com.mycelium.wallet.persistence;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.util.Log;

import com.mycelium.lt.api.model.TradeSessionStatus;

public class TradeSessionDb {

   private static final String LOG_TAG = "TradeSessionDb";

   private static final String TABLE_ACTIVE = "active";
   private static final String TABLE_VIEWTIME = "viewtime";

   private class OpenHelper extends SQLiteOpenHelper {

      private static final String DATABASE_NAME = "tradesession.db";
      private static final int DATABASE_VERSION = 16;

      public OpenHelper(Context context) {
         super(context, DATABASE_NAME, null, DATABASE_VERSION);
      }

      @Override
      public void onCreate(SQLiteDatabase db) {
         db.execSQL("CREATE TABLE active (id TEXT PRIMARY KEY, isBuy BOOLEAN, session BLOB);");
         db.execSQL("CREATE TABLE viewtime (id TEXT PRIMARY KEY, viewtime INTEGER);");
      }

      @Override
      public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
         db.execSQL("DROP TABLE IF EXISTS sessions");
         db.execSQL("DROP TABLE IF EXISTS active");
         db.execSQL("DROP TABLE IF EXISTS viewtime");
         onCreate(db);
      }
   }

   public class Entry {
      final UUID id;
      final long time;

      public Entry(UUID id, long time) {
         this.id = id;
         this.time = time;
      }
   }

   private OpenHelper _openHelper;
   private SQLiteDatabase _database;
   private SQLiteStatement _insert;
   private SQLiteStatement _updateSession;
   private SQLiteStatement _setViewTime;
   private SQLiteStatement _delete;
   private SQLiteStatement _deleteAll;
   private SQLiteStatement _countTradeSessions;
   private SQLiteStatement _countBuyTradeSessions;
   private SQLiteStatement _countSellTradeSessions;

   public TradeSessionDb(Context context) {
      _openHelper = new OpenHelper(context);
      _database = _openHelper.getWritableDatabase();
      _insert = _database.compileStatement("INSERT OR REPLACE INTO active VALUES (?,?,?)");
      _updateSession = _database.compileStatement("UPDATE active SET session=? WHERE id=?");
      _delete = _database.compileStatement("DELETE FROM active WHERE id = ?");
      _deleteAll = _database.compileStatement("DELETE FROM active");
      _countTradeSessions = _database.compileStatement("SELECT COUNT(*) FROM active");
      _countBuyTradeSessions = _database.compileStatement("SELECT COUNT(*) FROM active WHERE isBuy=1");
      _countSellTradeSessions = _database.compileStatement("SELECT COUNT(*) FROM active WHERE isBuy=0");
      _setViewTime = _database.compileStatement("INSERT OR REPLACE INTO viewtime  VALUES (?,?)");
   }

   public void close() {
      _openHelper.close();
   }

   /**
    * Get one session from the database
    */
   public TradeSessionStatus get(UUID id) {
      Cursor cursor = null;
      try {
         cursor = _database.query(TABLE_ACTIVE, new String[] { "session" }, "id = \"" + id.toString() + "\"", null,
               null, null, null);
         if (!cursor.moveToFirst()) {
            return null;
         }
         byte[] value = cursor.getBlob(0);
         return sessionFromBlob(value);
      } catch (Exception e) {
         Log.e(LOG_TAG, "Exception in get", e);
         return null;
      } finally {
         if (cursor != null) {
            cursor.close();
         }
      }
   }


   /**
    * Count the number of buy trade sessions
    */
   public int countTradeSessions() {
      try {
         return (int) _countTradeSessions.simpleQueryForLong();
      } catch (Exception e) {
         Log.e(LOG_TAG, "Exception in get", e);
         return 0;
      }
   }
   
   /**
    * Count the number of buy trade sessions
    */
   public int countBuyTradeSessions() {
      try {
         return (int) _countBuyTradeSessions.simpleQueryForLong();
      } catch (Exception e) {
         Log.e(LOG_TAG, "Exception in get", e);
         return 0;
      }
   }

   /**
    * Count the number of sell trade sessions
    */
   public int countSellTradeSessions() {
      try {
         return (int) _countSellTradeSessions.simpleQueryForLong();
      } catch (Exception e) {
         Log.e(LOG_TAG, "Exception in get", e);
         return 0;
      }
   }

   /**
    * Get all active sessions from the database
    */
   public Collection<TradeSessionStatus> getAll() {
      Cursor cursor = null;
      try {
         List<TradeSessionStatus> entries = new LinkedList<TradeSessionStatus>();
         cursor = _database.query(TABLE_ACTIVE, new String[] { "session" }, null, null, null, null, null);
         while (cursor.moveToNext()) {
            TradeSessionStatus session = sessionFromBlob(cursor.getBlob(0));
            if (session == null) {
               // Ignore anything we cannot parse... happens if the
               // serialization changes
               continue;
            }
            entries.add(session);
         }
         return entries;
      } catch (Exception e) {
         Log.e(LOG_TAG, "Exception in getAll", e);
         return null;
      } finally {
         if (cursor != null) {
            cursor.close();
         }
      }
   }

   /**
    * Get all sessions from the database
    */
   public Collection<TradeSessionStatus> getBuyTradeSessions() {
      Cursor cursor = null;
      try {
         List<TradeSessionStatus> entries = new LinkedList<TradeSessionStatus>();
         cursor = _database.query(TABLE_ACTIVE, new String[] { "session" }, "isBuy=?", new String[] { "1" }, null,
               null, null);
         while (cursor.moveToNext()) {
            TradeSessionStatus session = sessionFromBlob(cursor.getBlob(0));
            if (session == null) {
               // Ignore anything we cannot parse... happens if the
               // serialization changes
               continue;
            }
            entries.add(session);
         }
         return entries;
      } catch (Exception e) {
         Log.e(LOG_TAG, "Exception in getAll", e);
         return null;
      } finally {
         if (cursor != null) {
            cursor.close();
         }
      }
   }

   public Collection<TradeSessionStatus> getSellTradeSessions() {
      Cursor cursor = null;
      try {
         List<TradeSessionStatus> entries = new LinkedList<TradeSessionStatus>();
         cursor = _database.query(TABLE_ACTIVE, new String[] { "session" }, "isBuy=?", new String[] { "0" }, null,
               null, null);
         while (cursor.moveToNext()) {
            TradeSessionStatus session = sessionFromBlob(cursor.getBlob(0));
            if (session == null) {
               // Ignore anything we cannot parse... happens if the
               // serialization changes
               continue;
            }
            entries.add(session);
         }
         return entries;
      } catch (Exception e) {
         Log.e(LOG_TAG, "Exception in getAll", e);
         return null;
      } finally {
         if (cursor != null) {
            cursor.close();
         }
      }
   }

   /**
    * Insert a trade session, and set the viewed time to zero.
    */
   public synchronized void insert(TradeSessionStatus session) {
      try {
         _insert.bindString(1, session.id.toString());
         _insert.bindLong(2, session.isBuyer ? 1 : 0);
         _insert.bindBlob(3, sessionToBlob(session));
         _insert.executeInsert();
      } catch (Exception e) {
         Log.e(LOG_TAG, "Exception in insert", e);
         throw new RuntimeException(e);
      }
   }

   /**
    * Mark this session as viewed at its current lastChange timestamp
    */
   public synchronized void markViewed(TradeSessionStatus session) {
      try {
         _setViewTime.bindString(1, session.id.toString());
         _setViewTime.bindLong(2, session.lastChange);
         _setViewTime.execute();
      } catch (Exception e) {
         Log.e(LOG_TAG, "Exception in markViewed", e);
         throw new RuntimeException(e);
      }
   }

   /**
    * Get the view time of one session
    */
   public long getViewTimeById(UUID id) {
      Cursor cursor = null;
      try {
         cursor = _database.query(TABLE_VIEWTIME, new String[] { "viewtime" }, "id = \"" + id.toString() + "\"", null,
               null, null, null);
         if (!cursor.moveToFirst()) {
            return -1;
         }
         long value = cursor.getLong(0);
         return value;
      } catch (Exception e) {
         return -1;
      } finally {
         if (cursor != null) {
            cursor.close();
         }
      }
   }

   /**
    * Update a session which already exists in the database. This is without
    * changing its viewed status.
    */
   public synchronized void update(TradeSessionStatus session) {
      try {
         _updateSession.bindBlob(1, sessionToBlob(session));
         _updateSession.bindString(2, session.id.toString());
         _updateSession.execute();
      } catch (Exception e) {
         Log.e(LOG_TAG, "Exception in update", e);
         throw new RuntimeException(e);
      }
   }

   /**
    * Delete a session from the database
    * 
    * @param id
    */
   public synchronized void delete(UUID id) {
      try {
         _delete.bindString(1, id.toString());
         _delete.execute();
      } catch (Exception e) {
         Log.e(LOG_TAG, "Exception in delete", e);
         throw new RuntimeException(e);
      }
   }

   /**
    * Delete all sessions from the database
    * 
    * @param id
    */
   public synchronized void deleteAll() {
      try {
         _deleteAll.execute();
      } catch (Exception e) {
         Log.e(LOG_TAG, "Exception in delete All", e);
         throw new RuntimeException(e);
      }
   }

   private static byte[] sessionToBlob(TradeSessionStatus session) throws IOException {
      ByteArrayOutputStream bout = new ByteArrayOutputStream(1024);
      ObjectOutputStream out = new ObjectOutputStream(bout);
      out.writeObject(session);
      out.close();
      byte[] result = bout.toByteArray();
      bout.close();
      return result;
   }

   private static TradeSessionStatus sessionFromBlob(byte[] blob) throws IOException {
      ByteArrayInputStream bin = new ByteArrayInputStream(blob);
      ObjectInputStream in = new ObjectInputStream(bin);
      TradeSessionStatus session;
      try {
         session = (TradeSessionStatus) in.readObject();
      } catch (ClassNotFoundException e) {
         return null;
      }
      in.close();
      bin.close();
      return session;
   }

}
