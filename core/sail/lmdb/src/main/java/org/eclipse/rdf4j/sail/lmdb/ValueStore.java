/*******************************************************************************
 * Copyright (c) 2015 Eclipse RDF4J contributors, Aduna, and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Distribution License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *******************************************************************************/
package org.eclipse.rdf4j.sail.lmdb;

import static org.eclipse.rdf4j.sail.lmdb.LmdbUtil.E;
import static org.eclipse.rdf4j.sail.lmdb.LmdbUtil.openDatabase;
import static org.eclipse.rdf4j.sail.lmdb.LmdbUtil.readTransaction;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.util.lmdb.LMDB.MDB_CREATE;
import static org.lwjgl.util.lmdb.LMDB.MDB_NEXT;
import static org.lwjgl.util.lmdb.LMDB.MDB_NOMETASYNC;
import static org.lwjgl.util.lmdb.LMDB.MDB_NOSYNC;
import static org.lwjgl.util.lmdb.LMDB.MDB_NOTLS;
import static org.lwjgl.util.lmdb.LMDB.MDB_PREV;
import static org.lwjgl.util.lmdb.LMDB.MDB_SET_RANGE;
import static org.lwjgl.util.lmdb.LMDB.mdb_cursor_close;
import static org.lwjgl.util.lmdb.LMDB.mdb_cursor_get;
import static org.lwjgl.util.lmdb.LMDB.mdb_cursor_open;
import static org.lwjgl.util.lmdb.LMDB.mdb_env_close;
import static org.lwjgl.util.lmdb.LMDB.mdb_env_create;
import static org.lwjgl.util.lmdb.LMDB.mdb_env_get_maxkeysize;
import static org.lwjgl.util.lmdb.LMDB.mdb_env_open;
import static org.lwjgl.util.lmdb.LMDB.mdb_env_set_mapsize;
import static org.lwjgl.util.lmdb.LMDB.mdb_get;
import static org.lwjgl.util.lmdb.LMDB.mdb_put;
import static org.lwjgl.util.lmdb.LMDB.mdb_txn_abort;
import static org.lwjgl.util.lmdb.LMDB.mdb_txn_begin;
import static org.lwjgl.util.lmdb.LMDB.mdb_txn_commit;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.zip.CRC32;

import org.eclipse.rdf4j.common.concurrent.locks.Lock;
import org.eclipse.rdf4j.common.concurrent.locks.ReadWriteLockManager;
import org.eclipse.rdf4j.common.concurrent.locks.WritePrefReadWriteLockManager;
import org.eclipse.rdf4j.common.io.ByteArrayUtil;
import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.base.AbstractValueFactory;
import org.eclipse.rdf4j.model.util.Literals;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.sail.SailException;
import org.eclipse.rdf4j.sail.lmdb.LmdbUtil.Transaction;
import org.eclipse.rdf4j.sail.lmdb.model.LmdbBNode;
import org.eclipse.rdf4j.sail.lmdb.model.LmdbIRI;
import org.eclipse.rdf4j.sail.lmdb.model.LmdbLiteral;
import org.eclipse.rdf4j.sail.lmdb.model.LmdbResource;
import org.eclipse.rdf4j.sail.lmdb.model.LmdbValue;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.lmdb.MDBVal;

/**
 * LMDB-based indexed storage and retrieval of RDF values. ValueStore maps RDF values to integer IDs and vice-versa.
 *
 * @author Arjohn Kampman
 * @author Ken Wenzel
 */
class ValueStore extends AbstractValueFactory {

	/*-----------*
	 * Constants *
	 *-----------*/

	/**
	 * The default value cache size.
	 */
	public static final int VALUE_CACHE_SIZE = 512;

	/**
	 * The default value id cache size.
	 */
	public static final int VALUE_ID_CACHE_SIZE = 128;

	/**
	 * The default namespace cache size.
	 */
	public static final int NAMESPACE_CACHE_SIZE = 64;

	/**
	 * The default namespace id cache size.
	 */
	public static final int NAMESPACE_ID_CACHE_SIZE = 32;

	private static final String FILENAME_PREFIX = "values";

	private static final byte ID_KEY = 0x0; // 0000 0000

	private static final byte HASH_KEY = 0x1; // 0000 0001

	private static final byte URI_VALUE = 0x1; // 0000 0001

	private static final byte BNODE_VALUE = 0x2; // 0000 0010

	private static final byte LITERAL_VALUE = 0x3; // 0000 0011

	/*-----------*
	 * Variables *
	 *-----------*/
	/**
	 * The default byte order for all byte buffers
	 */
	private static ByteOrder BYTE_ORDER = ByteOrder.BIG_ENDIAN;
	/**
	 * Used to do the actual storage of values, once they're translated to byte arrays.
	 */
	private final File dbDir;
	/**
	 * Lock manager used to prevent the removal of values over multiple method calls. Note that values can still be
	 * added when read locks are active.
	 */
	private final ReadWriteLockManager lockManager = new WritePrefReadWriteLockManager();
	/**
	 * A simple cache containing the [VALUE_CACHE_SIZE] most-recently used values stored by their ID.
	 */
	private final ConcurrentCache<Integer, LmdbValue> valueCache;
	/**
	 * A simple cache containing the [ID_CACHE_SIZE] most-recently used value-IDs stored by their value.
	 */
	private final ConcurrentCache<LmdbValue, Integer> valueIDCache;
	/**
	 * A simple cache containing the [NAMESPACE_CACHE_SIZE] most-recently used namespaces stored by their ID.
	 */
	private final ConcurrentCache<Integer, String> namespaceCache;
	/**
	 * A simple cache containing the [NAMESPACE_ID_CACHE_SIZE] most-recently used namespace-IDs stored by their
	 * namespace.
	 */
	private final ConcurrentCache<String, Integer> namespaceIDCache;
	/**
	 * Used to do the actual storage of values, once they're translated to byte arrays.
	 */
	private long env;
	private int dbi;
	private long writeTxn;

	/**
	 * An object that indicates the revision of the value store, which is used to check if cached value IDs are still
	 * valid. In order to be valid, the ValueStoreRevision object of a LmdbValue needs to be equal to this object.
	 */
	private volatile ValueStoreRevision revision;
	/**
	 * The next ID that is associated with a stored value
	 */
	private long nextId;
	/**
	 * A key to store the next ID within the database
	 */
	private byte[] nextIdKey = "__NEXT_ID\u0000".getBytes(StandardCharsets.UTF_8);

	/*--------------*
	 * Constructors *
	 *--------------*/

	public ValueStore(File dataDir) throws IOException {
		this(dataDir, false);
	}

	public ValueStore(File dataDir, boolean forceSync) throws IOException {
		this(dataDir, forceSync, VALUE_CACHE_SIZE, VALUE_ID_CACHE_SIZE, NAMESPACE_CACHE_SIZE, NAMESPACE_ID_CACHE_SIZE);
	}

	public ValueStore(File dataDir, boolean forceSync, int valueCacheSize, int valueIDCacheSize, int namespaceCacheSize,
			int namespaceIDCacheSize) throws IOException {
		this.dbDir = new File(dataDir, FILENAME_PREFIX);
		open();

		valueCache = new ConcurrentCache<>(valueCacheSize);
		valueIDCache = new ConcurrentCache<>(valueIDCacheSize);
		namespaceCache = new ConcurrentCache<>(namespaceCacheSize);
		namespaceIDCache = new ConcurrentCache<>(namespaceIDCacheSize);

		setNewRevision();
		// read maximum id from store
		LmdbUtil.<Long>readTransaction(env, (stack, txn) -> {
			long cursor = 0;
			try {
				PointerBuffer pp = stack.mallocPointer(1);
				E(mdb_cursor_open(txn, dbi, pp));
				cursor = pp.get(0);

				MDBVal keyData = MDBVal.callocStack(stack);
				// set cursor to min key
				keyData.mv_data(stack.bytes(new byte[] { ID_KEY, (byte) 0xFF }));
				MDBVal valueData = MDBVal.callocStack(stack);
				if (mdb_cursor_get(cursor, keyData, valueData, MDB_SET_RANGE) == 0 &&
						mdb_cursor_get(cursor, keyData, valueData, MDB_PREV) == 0) {
					byte[] value = new byte[keyData.mv_data().remaining()];
					keyData.mv_data().get(value);
					nextId = data2id(value) + 1;
				} else {
					nextId = 1;
				}
				return null;
			} finally {
				if (cursor != 0) {
					mdb_cursor_close(cursor);
				}
			}
		});
	}

	public static void main(String[] args) throws Exception {
		File dataDir = new File(args[0]);
		ValueStore valueStore = new ValueStore(dataDir);

		int maxID = (int) valueStore.nextId - 1;
		for (int id = 1; id <= maxID; id++) {
			byte[] data = valueStore.getData(id);
			if (valueStore.isNamespaceData(data)) {
				String ns = valueStore.data2namespace(data);
				System.out.println("[" + id + "] " + ns);
			} else {
				Value value = valueStore.data2value(id, data);
				System.out.println("[" + id + "] " + value.toString());
			}
		}
	}

	/*---------*
	 * Methods *
	 *---------*/

	private void open() throws IOException {
		// create directory if it not exists
		dbDir.mkdirs();

		try (MemoryStack stack = stackPush()) {
			PointerBuffer pp = stack.mallocPointer(1);
			E(mdb_env_create(pp));
			env = pp.get(0);
		}

		// 1 TB for 64-Bit systems
		// mdb_env_set_mapsize(env, 1_099_511_627_776L);
		mdb_env_set_mapsize(env, 1_099_511_627L);

		// Open environment
		E(mdb_env_open(env, dbDir.getPath(), MDB_NOTLS | MDB_NOSYNC | MDB_NOMETASYNC, 0664));

		// Open database
		dbi = openDatabase(env, null, MDB_CREATE, null);
	}

	private long nextId() throws IOException {
		long result = nextId;
		nextId++;
		return result;
	}

	protected byte[] toBytes(long value) {
		return ByteBuffer.wrap(new byte[Long.BYTES])
				.order(BYTE_ORDER)
				.putLong(value)
				.array();
	}

	protected byte[] id2data(long id) {
		return ByteBuffer.wrap(new byte[1 + Long.BYTES])
				.order(BYTE_ORDER)
				.put(ID_KEY)
				.putLong(id)
				.array();
	}

	protected int data2id(byte[] idData) {
		ByteBuffer bb = ByteBuffer.wrap(idData).order(BYTE_ORDER);
		// skip id marker
		bb.get();
		return (int) bb.getLong();
	}

	/**
	 * Creates a new revision object for this value store, invalidating any IDs cached in LmdbValue objects that were
	 * created by this value store.
	 */
	private void setNewRevision() {
		revision = new ValueStoreRevision(this);
	}

	public ValueStoreRevision getRevision() {
		return revision;
	}

	/**
	 * Gets a read lock on this value store that can be used to prevent values from being removed while the lock is
	 * active.
	 */
	public Lock getReadLock() throws InterruptedException {
		return lockManager.getReadLock();
	}

	protected byte[] getData(int id) throws IOException {
		return readTransaction(env, (stack, txn) -> {
			MDBVal keyData = MDBVal.callocStack(stack);
			keyData.mv_data(stack.bytes(id2data(id)));
			MDBVal valueData = MDBVal.callocStack(stack);
			if (mdb_get(txn, dbi, keyData, valueData) == 0) {
				byte[] valueBytes = new byte[valueData.mv_data().remaining()];
				valueData.mv_data().get(valueBytes);
				return valueBytes;
			}
			return null;
		});
	}

	/**
	 * Gets the value for the specified ID.
	 *
	 * @param id A value ID.
	 * @return The value for the ID, or <tt>null</tt> no such value could be found.
	 * @throws IOException If an I/O error occurred.
	 */
	public LmdbValue getValue(int id) throws IOException {
		// Check value cache
		Integer cacheID = id;
		LmdbValue resultValue = valueCache.get(cacheID);

		if (resultValue == null) {
			// Value not in cache, fetch it from file
			byte[] data = getData(id);

			if (data != null) {
				resultValue = data2value(id, data);
				// Store value in cache
				valueCache.put(cacheID, resultValue);
			}
		}

		return resultValue;
	}

	private int findId(byte[] data) throws IOException {
		Long id = LmdbUtil.<Long>readTransaction(env, (stack, txn) -> {
			if (data.length < mdb_env_get_maxkeysize(env)) {
				MDBVal keyData = MDBVal.callocStack(stack);
				keyData.mv_data(stack.bytes(data));
				MDBVal valueData = MDBVal.callocStack(stack);
				if (mdb_get(txn, dbi, keyData, valueData) == 0) {
					byte[] valueBytes = new byte[valueData.mv_data().remaining()];
					valueData.mv_data().get(valueBytes);
					return (long) data2id(valueBytes);
				}
			} else {
				ByteBuffer dataBb = ByteBuffer.wrap(data);
				long dataHash = hash(data);
				byte[] hashAndNr = new byte[1 + Long.BYTES * 2];
				ByteBuffer hashBb = ByteBuffer.wrap(hashAndNr);
				hashBb.put(HASH_KEY);
				hashBb.putLong(dataHash);
				hashBb.putLong(0);

				MDBVal keyData = MDBVal.callocStack(stack);
				// set cursor to min key
				keyData.mv_data(stack.bytes(hashAndNr));
				MDBVal valueData = MDBVal.callocStack(stack);

				long cursor = 0;
				try {
					PointerBuffer pp = stack.mallocPointer(1);
					E(mdb_cursor_open(txn, dbi, pp));
					cursor = pp.get(0);

					if (mdb_cursor_get(cursor, keyData, valueData, MDB_SET_RANGE) == 0) {
						do {
							if (compareRegion(keyData.mv_data(), 0, hashBb, 0, 1 + Long.BYTES) != 0) {
								break;
							}

							if (valueData.mv_data().compareTo(dataBb) == 0) {
								byte[] idBytes = new byte[keyData.mv_data().remaining()];
								keyData.mv_data().get(idBytes);
								return (long) data2id(idBytes);
							}
						} while (mdb_cursor_get(cursor, keyData, valueData, MDB_NEXT) == 0);
					}
				} finally {
					if (cursor != 0) {
						mdb_cursor_close(cursor);
					}
				}
			}
			return null;
		});
		return id != null ? id.intValue() : LmdbValue.UNKNOWN_ID;
	}

	<T> T writeTransaction(Transaction<T> transaction) throws IOException {
		if (writeTxn != 0) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				return transaction.exec(stack, writeTxn);
			}
		} else {
			return LmdbUtil.transaction(env, transaction);
		}
	}

	private void storeId(int id, byte[] data) throws IOException {
		this.<Long>writeTransaction((stack, txn) -> {
			if (data.length < mdb_env_get_maxkeysize(env)) {
				MDBVal dataVal = MDBVal.callocStack(stack);
				dataVal.mv_data(stack.bytes(data));
				MDBVal idVal = MDBVal.callocStack(stack);
				idVal.mv_data(stack.bytes(id2data(id)));

				mdb_put(txn, dbi, dataVal, idVal, 0);
				mdb_put(txn, dbi, idVal, dataVal, 0);
			} else {
				long dataHash = hash(data);
				byte[] hashAndNr = new byte[1 + Long.BYTES * 2];
				ByteBuffer hashBb = ByteBuffer.wrap(hashAndNr);
				hashBb.put(HASH_KEY);
				hashBb.putLong(dataHash);
				hashBb.putLong(1 + Long.BYTES, 0);

				MDBVal keyData = MDBVal.callocStack(stack);
				// set cursor to min key
				keyData.mv_data(stack.bytes(hashAndNr));
				MDBVal valueData = MDBVal.callocStack(stack);

				long hashNr = 0;
				long cursor = 0;
				try {
					PointerBuffer pp = stack.mallocPointer(1);
					E(mdb_cursor_open(txn, dbi, pp));
					cursor = pp.get(0);

					if (mdb_cursor_get(cursor, keyData, valueData, MDB_SET_RANGE) == 0) {
						do {
							hashNr++;
							if (compareRegion(keyData.mv_data(), 0, hashBb, 0, 1 + Long.BYTES) != 0) {
								break;
							}
						} while (mdb_cursor_get(cursor, keyData, valueData, MDB_NEXT) == 0);
					}
				} finally {
					if (cursor != 0) {
						mdb_cursor_close(cursor);
					}
				}

				hashBb.putLong(1 + Long.BYTES, hashNr);
				byte[] idData = id2data(id);
				keyData = MDBVal.callocStack(stack);
				keyData.mv_data(stack.bytes(hashAndNr));
				valueData = MDBVal.callocStack(stack);
				valueData.mv_data(stack.bytes(idData));
				mdb_put(txn, dbi, keyData, valueData, 0);

				MDBVal realValueData = MDBVal.callocStack(stack);
				ByteBuffer dataBuffer = null;
				try {
					dataBuffer = MemoryUtil.memAlloc(data.length);
					dataBuffer.put(data);
					dataBuffer.flip();
					realValueData.mv_data(dataBuffer);
					mdb_put(txn, dbi, valueData, realValueData, 0);
				} finally {
					if (dataBuffer != null) {
						MemoryUtil.memFree(dataBuffer);
					}
				}
			}
			return null;
		});
	}

	int compareRegion(ByteBuffer array1, int startIdx1, ByteBuffer array2, int startIdx2, int length) {
		int result = 0;
		for (int i = 0; result == 0 && i < length; i++) {
			result = (array1.get(startIdx1 + i) & 0xff) - (array2.get(startIdx2 + i) & 0xff);
		}
		return result;
	}

	/**
	 * Gets the ID for the specified value.
	 *
	 * @param value A value.
	 * @return The ID for the specified value, or {@link LmdbValue#UNKNOWN_ID} if no such ID could be found.
	 * @throws IOException If an I/O error occurred.
	 */
	public int getID(Value value) throws IOException {
		// Try to get the internal ID from the value itself
		boolean isOwnValue = isOwnValue(value);

		if (isOwnValue) {
			LmdbValue lmdbValue = (LmdbValue) value;

			if (revisionIsCurrent(lmdbValue)) {
				int id = lmdbValue.getInternalID();

				if (id != LmdbValue.UNKNOWN_ID) {
					return id;
				}
			}
		}

		// Check cache
		Integer cachedID = valueIDCache.get(value);

		if (cachedID != null) {
			int id = cachedID.intValue();

			if (isOwnValue) {
				// Store id in value for fast access in any consecutive calls
				((LmdbValue) value).setInternalID(id, revision);
			}

			return id;
		}

		// ID not cached, search in file
		byte[] data = value2data(value, false);
		if (data == null && value instanceof Literal) {
			data = literal2legacy((Literal) value);
		}

		if (data != null) {
			int id = findId(data);

			if (id != LmdbValue.UNKNOWN_ID) {
				if (isOwnValue) {
					// Store id in value for fast access in any consecutive calls
					((LmdbValue) value).setInternalID(id, revision);
				} else {
					// Store id in cache
					LmdbValue nv = getLmdbValue(value);
					nv.setInternalID(id, revision);
					valueIDCache.put(nv, id);
				}
			}

			return id;
		}

		return LmdbValue.UNKNOWN_ID;
	}

	public void startTransaction() throws IOException {
		try (MemoryStack stack = stackPush()) {
			PointerBuffer pp = stack.mallocPointer(1);

			E(mdb_txn_begin(env, NULL, 0, pp));
			writeTxn = pp.get(0);
		}
	}

	/**
	 * Closes the snapshot and the DB iterator if any was opened in the current transaction
	 */
	void endTransaction(boolean commit) throws IOException {
		if (writeTxn != 0) {
			if (commit) {
				mdb_txn_commit(writeTxn);
			} else {
				mdb_txn_abort(writeTxn);
			}
			writeTxn = 0;
		}
	}

	public void commit() throws IOException {
		endTransaction(true);
	}

	public void rollback() throws IOException {
		endTransaction(false);
	}

	/**
	 * Stores the supplied value and returns the ID that has been assigned to it. In case the value was already present,
	 * the value will not be stored again and the ID of the existing value is returned.
	 *
	 * @param value The Value to store.
	 * @return The ID that has been assigned to the value.
	 * @throws IOException If an I/O error occurred.
	 */
	public int storeValue(Value value) throws IOException {
		int id = getID(value);

		if (id == LmdbValue.UNKNOWN_ID) {
			// Unable to get internal ID in a cheap way, just store it in the data
			// store which will handle duplicates
			byte[] valueData = value2data(value, true);

			id = (int) nextId();
			storeId(id, valueData);

			LmdbValue nv = isOwnValue(value) ? (LmdbValue) value : getLmdbValue(value);

			// Store id in value for fast access in any consecutive calls
			nv.setInternalID(id, revision);

			// Update cache
			valueIDCache.put(nv, id);
		}

		return id;
	}

	/**
	 * Computes a hash code for the supplied data.
	 *
	 * @param data The data to calculate the hash code for.
	 * @return A hash code for the supplied data.
	 */
	private long hash(byte[] data) {
		CRC32 crc32 = new CRC32();
		crc32.update(data);
		return crc32.getValue();
	}

	/**
	 * Removes all values from the ValueStore.
	 *
	 * @throws IOException If an I/O error occurred.
	 */
	public void clear() throws IOException {
		try {
			Lock writeLock = lockManager.getWriteLock();
			try {
				close();

				new File(dbDir, "data.mdb").delete();
				new File(dbDir, "lock.mdb").delete();

				valueCache.clear();
				valueIDCache.clear();
				namespaceCache.clear();
				namespaceIDCache.clear();

				open();

				setNewRevision();
			} finally {
				writeLock.release();
			}
		} catch (InterruptedException e) {
			throw new IOException("Failed to acquire write lock", e);
		}
	}

	/**
	 * Synchronizes any changes that are cached in memory to disk.
	 *
	 * @throws IOException If an I/O error occurred.
	 */
	public void sync() throws IOException {
		// TODO correctly handle sync
		// db.sync();
	}

	/**
	 * Closes the ValueStore, releasing any file references, etc. Once closed, the ValueStore can no longer be used.
	 *
	 * @throws IOException If an I/O error occurred.
	 */
	public void close() throws IOException {
		if (env != 0) {
			endTransaction(false);
			mdb_env_close(env);
			env = 0;
		}
	}

	/**
	 * Checks that every value has exactly one ID.
	 *
	 * @throws IOException
	 */
	public void checkConsistency() throws SailException, IOException {
		int maxID = (int) nextId - 1;
		for (int id = 1; id <= maxID; id++) {
			byte[] data = getData(id);
			if (isNamespaceData(data)) {
				String namespace = data2namespace(data);
				try {
					if (id == getNamespaceID(namespace, false)
							&& java.net.URI.create(namespace + "part").isAbsolute()) {
						continue;
					}
				} catch (IllegalArgumentException e) {
					// throw SailException
				}
				throw new SailException(
						"Store must be manually exported and imported to fix namespaces like " + namespace);
			} else {
				Value value = this.data2value(id, data);
				if (id != this.getID(copy(value))) {
					throw new SailException(
							"Store must be manually exported and imported to merge values like " + value);
				}
			}
		}
	}

	private Value copy(Value value) {
		if (value instanceof IRI) {
			return createIRI(value.stringValue());
		} else if (value instanceof Literal) {
			Literal lit = (Literal) value;
			if (Literals.isLanguageLiteral(lit)) {
				return createLiteral(value.stringValue(), lit.getLanguage().orElse(null));
			} else {
				return createLiteral(value.stringValue(), lit.getDatatype());
			}
		} else {
			return createBNode(value.stringValue());
		}
	}

	/**
	 * Checks if the supplied Value object is a LmdbValue object that has been created by this ValueStore.
	 */
	private boolean isOwnValue(Value value) {
		return value instanceof LmdbValue && ((LmdbValue) value).getValueStoreRevision().getValueStore() == this;
	}

	/**
	 * Checks if the revision of the supplied value object is still current.
	 */
	private boolean revisionIsCurrent(LmdbValue value) {
		return revision.equals(value.getValueStoreRevision());
	}

	private byte[] value2data(Value value, boolean create) throws IOException {
		if (value instanceof IRI) {
			return uri2data((IRI) value, create);
		} else if (value instanceof BNode) {
			return bnode2data((BNode) value, create);
		} else if (value instanceof Literal) {
			return literal2data((Literal) value, create);
		} else {
			throw new IllegalArgumentException("value parameter should be a URI, BNode or Literal");
		}
	}

	private byte[] uri2data(IRI uri, boolean create) throws IOException {
		int nsID = getNamespaceID(uri.getNamespace(), create);

		if (nsID == -1) {
			// Unknown namespace means unknown URI
			return null;
		}

		// Get local name in UTF-8
		byte[] localNameData = uri.getLocalName().getBytes(StandardCharsets.UTF_8);

		// Combine parts in a single byte array
		byte[] uriData = new byte[5 + localNameData.length];
		uriData[0] = URI_VALUE;
		ByteArrayUtil.putInt(nsID, uriData, 1);
		ByteArrayUtil.put(localNameData, uriData, 5);

		return uriData;
	}

	private byte[] bnode2data(BNode bNode, boolean create) throws IOException {
		byte[] idData = bNode.getID().getBytes(StandardCharsets.UTF_8);

		byte[] bNodeData = new byte[1 + idData.length];
		bNodeData[0] = BNODE_VALUE;
		ByteArrayUtil.put(idData, bNodeData, 1);

		return bNodeData;
	}

	private byte[] literal2data(Literal literal, boolean create) throws IOException {
		return literal2data(literal.getLabel(), literal.getLanguage(), literal.getDatatype(), create);
	}

	private byte[] literal2legacy(Literal literal) throws IOException {
		IRI dt = literal.getDatatype();
		if (XSD.STRING.equals(dt) || RDF.LANGSTRING.equals(dt)) {
			return literal2data(literal.getLabel(), literal.getLanguage(), null, false);
		}
		return literal2data(literal.getLabel(), literal.getLanguage(), dt, false);
	}

	private byte[] literal2data(String label, Optional<String> lang, IRI dt, boolean create)
			throws IOException, UnsupportedEncodingException {
		// Get datatype ID
		int datatypeID = LmdbValue.UNKNOWN_ID;

		if (create) {
			datatypeID = storeValue(dt);
		} else if (dt != null) {
			datatypeID = getID(dt);

			if (datatypeID == LmdbValue.UNKNOWN_ID) {
				// Unknown datatype means unknown literal
				return null;
			}
		}

		// Get language tag in UTF-8
		byte[] langData = null;
		int langDataLength = 0;
		if (lang.isPresent()) {
			langData = lang.get().getBytes(StandardCharsets.UTF_8);
			langDataLength = langData.length;
		}

		// Get label in UTF-8
		byte[] labelData = label.getBytes(StandardCharsets.UTF_8);

		// Combine parts in a single byte array
		byte[] literalData = new byte[6 + langDataLength + labelData.length];
		literalData[0] = LITERAL_VALUE;
		ByteArrayUtil.putInt(datatypeID, literalData, 1);
		literalData[5] = (byte) langDataLength;
		if (langData != null) {
			ByteArrayUtil.put(langData, literalData, 6);
		}
		ByteArrayUtil.put(labelData, literalData, 6 + langDataLength);

		return literalData;
	}

	private boolean isNamespaceData(byte[] data) {
		return data[0] != URI_VALUE && data[0] != BNODE_VALUE && data[0] != LITERAL_VALUE;
	}

	private LmdbValue data2value(int id, byte[] data) throws IOException {
		switch (data[0]) {
		case URI_VALUE:
			return data2uri(id, data);
		case BNODE_VALUE:
			return data2bnode(id, data);
		case LITERAL_VALUE:
			return data2literal(id, data);
		default:
			throw new IllegalArgumentException("Namespaces cannot be converted into values: " + data2namespace(data));
		}
	}

	private LmdbIRI data2uri(int id, byte[] data) throws IOException {
		int nsID = ByteArrayUtil.getInt(data, 1);
		String namespace = getNamespace(nsID);

		String localName = new String(data, 5, data.length - 5, StandardCharsets.UTF_8);

		return new LmdbIRI(revision, namespace, localName, id);
	}

	private LmdbBNode data2bnode(int id, byte[] data) throws IOException {
		String nodeID = new String(data, 1, data.length - 1, StandardCharsets.UTF_8);
		return new LmdbBNode(revision, nodeID, id);
	}

	private LmdbLiteral data2literal(int id, byte[] data) throws IOException {
		// Get datatype
		int datatypeID = ByteArrayUtil.getInt(data, 1);
		IRI datatype = null;
		if (datatypeID != LmdbValue.UNKNOWN_ID) {
			datatype = (IRI) getValue(datatypeID);
		}

		// Get language tag
		String lang = null;
		int langLength = data[5];
		if (langLength > 0) {
			lang = new String(data, 6, langLength, StandardCharsets.UTF_8);
		}

		// Get label
		String label = new String(data, 6 + langLength, data.length - 6 - langLength, StandardCharsets.UTF_8);

		if (lang != null) {
			return new LmdbLiteral(revision, label, lang, id);
		} else if (datatype != null) {
			return new LmdbLiteral(revision, label, datatype, id);
		} else {
			return new LmdbLiteral(revision, label, XSD.STRING, id);
		}
	}

	private String data2namespace(byte[] data) throws UnsupportedEncodingException {
		return new String(data, StandardCharsets.UTF_8);
	}

	private int getNamespaceID(String namespace, boolean create) throws IOException {
		Integer cacheID = namespaceIDCache.get(namespace);
		if (cacheID != null) {
			return cacheID;
		}

		byte[] namespaceData = namespace.getBytes(StandardCharsets.UTF_8);

		int id = findId(namespaceData);
		if (id == LmdbValue.UNKNOWN_ID && create) {
			id = (int) nextId();
			storeId(id, namespaceData);
		}

		if (id != LmdbValue.UNKNOWN_ID) {
			namespaceIDCache.put(namespace, id);
		}

		return id;
	}

	/*-------------------------------------*
	 * Methods from interface ValueFactory *
	 *-------------------------------------*/

	private String getNamespace(int id) throws IOException {
		Integer cacheID = id;
		String namespace = namespaceCache.get(cacheID);

		if (namespace == null) {
			namespace = readTransaction(env, (stack, txn) -> {
				MDBVal keyData = MDBVal.callocStack(stack);
				keyData.mv_data(stack.bytes(id2data(id)));
				MDBVal valueData = MDBVal.callocStack(stack);
				if (mdb_get(txn, dbi, keyData, valueData) == 0) {
					byte[] valueBytes = new byte[valueData.mv_data().remaining()];
					valueData.mv_data().get(valueBytes);
					return data2namespace(valueBytes);
				}
				return null;
			});

			namespaceCache.put(cacheID, namespace);
		}

		return namespace;
	}

	@Override
	public LmdbIRI createIRI(String uri) {
		return new LmdbIRI(revision, uri);
	}

	@Override
	public LmdbIRI createIRI(String namespace, String localName) {
		return new LmdbIRI(revision, namespace, localName);
	}

	@Override
	public LmdbBNode createBNode(String nodeID) {
		return new LmdbBNode(revision, nodeID);
	}

	@Override
	public LmdbLiteral createLiteral(String value) {
		return new LmdbLiteral(revision, value, XSD.STRING);
	}

	@Override
	public LmdbLiteral createLiteral(String value, String language) {
		return new LmdbLiteral(revision, value, language);
	}

	/*----------------------------------------------------------------------*
	 * Methods for converting model objects to LmdbStore-specific objects *
	 *----------------------------------------------------------------------*/

	@Override
	public LmdbLiteral createLiteral(String value, IRI datatype) {
		return new LmdbLiteral(revision, value, datatype);
	}

	public LmdbValue getLmdbValue(Value value) {
		if (value instanceof Resource) {
			return getLmdbResource((Resource) value);
		} else if (value instanceof Literal) {
			return getLmdbLiteral((Literal) value);
		} else {
			throw new IllegalArgumentException("Unknown value type: " + value.getClass());
		}
	}

	public LmdbResource getLmdbResource(Resource resource) {
		if (resource instanceof IRI) {
			return getLmdbURI((IRI) resource);
		} else if (resource instanceof BNode) {
			return getLmdbBNode((BNode) resource);
		} else {
			throw new IllegalArgumentException("Unknown resource type: " + resource.getClass());
		}
	}

	/**
	 * Creates a LmdbURI that is equal to the supplied URI. This method returns the supplied URI itself if it is already
	 * a LmdbURI that has been created by this ValueStore, which prevents unnecessary object creations.
	 *
	 * @return A LmdbURI for the specified URI.
	 */
	public LmdbIRI getLmdbURI(IRI uri) {
		if (isOwnValue(uri)) {
			return (LmdbIRI) uri;
		}

		return new LmdbIRI(revision, uri.toString());
	}

	/**
	 * Creates a LmdbBNode that is equal to the supplied bnode. This method returns the supplied bnode itself if it is
	 * already a LmdbBNode that has been created by this ValueStore, which prevents unnecessary object creations.
	 *
	 * @return A LmdbBNode for the specified bnode.
	 */
	public LmdbBNode getLmdbBNode(BNode bnode) {
		if (isOwnValue(bnode)) {
			return (LmdbBNode) bnode;
		}

		return new LmdbBNode(revision, bnode.getID());
	}

	/*--------------------*
	 * Test/debug methods *
	 *--------------------*/

	/**
	 * Creates an LmdbLiteral that is equal to the supplied literal. This method returns the supplied literal itself if
	 * it is already a LmdbLiteral that has been created by this ValueStore, which prevents unnecessary object
	 * creations.
	 *
	 * @return A LmdbLiteral for the specified literal.
	 */
	public LmdbLiteral getLmdbLiteral(Literal l) {
		if (isOwnValue(l)) {
			return (LmdbLiteral) l;
		}

		if (Literals.isLanguageLiteral(l)) {
			return new LmdbLiteral(revision, l.getLabel(), l.getLanguage().get());
		} else {
			LmdbIRI datatype = getLmdbURI(l.getDatatype());
			return new LmdbLiteral(revision, l.getLabel(), datatype);
		}
	}
}
