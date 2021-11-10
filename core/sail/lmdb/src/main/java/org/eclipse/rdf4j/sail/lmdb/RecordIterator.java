/*******************************************************************************
 * Copyright (c) 2015 Eclipse RDF4J contributors, Aduna, and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Distribution License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *******************************************************************************/
package org.eclipse.rdf4j.sail.lmdb;

import java.io.Closeable;
import java.io.IOException;

/**
 * An iterator that iterates over records, for example those in a key-value database.
 *
 * @author Arjohn Kampman
 * @author Ken Wenzel
 */
public interface RecordIterator extends Closeable {

	/**
	 * Returns the next record in the BTree.
	 *
	 * @return A record that is stored in the BTree, or <tt>null</tt> if all records have been returned.
	 * @exception IOException In case an I/O error occurred.
	 */
	byte[] next() throws IOException;

	/**
	 * Closes the iterator, freeing any resources that it uses. Once closed, the iterator will not return any more
	 * records.
	 *
	 * @exception IOException In case an I/O error occurred.
	 */
	@Override
	void close() throws IOException;
}
