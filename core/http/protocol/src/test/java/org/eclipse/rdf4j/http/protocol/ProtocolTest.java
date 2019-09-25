/*******************************************************************************
 * Copyright (c) 2015 Eclipse RDF4J contributors, Aduna, and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Distribution License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *******************************************************************************/

package org.eclipse.rdf4j.http.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.rdf4j.http.protocol.Protocol.CONFIG;
import static org.eclipse.rdf4j.http.protocol.Protocol.CONTEXTS;
import static org.eclipse.rdf4j.http.protocol.Protocol.NAMESPACES;
import static org.eclipse.rdf4j.http.protocol.Protocol.PROTOCOL;
import static org.eclipse.rdf4j.http.protocol.Protocol.REPOSITORIES;
import static org.eclipse.rdf4j.http.protocol.Protocol.getConfigLocation;
import static org.eclipse.rdf4j.http.protocol.Protocol.getContextsLocation;
import static org.eclipse.rdf4j.http.protocol.Protocol.getNamespacesLocation;
import static org.eclipse.rdf4j.http.protocol.Protocol.getProtocolLocation;
import static org.eclipse.rdf4j.http.protocol.Protocol.getRepositoriesLocation;
import static org.eclipse.rdf4j.http.protocol.Protocol.getRepositoryConfigLocation;
import static org.eclipse.rdf4j.http.protocol.Protocol.getRepositoryID;
import static org.eclipse.rdf4j.http.protocol.Protocol.getRepositoryLocation;
import static org.eclipse.rdf4j.http.protocol.Protocol.getServerLocation;
import static org.junit.Assert.assertEquals;

import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.Test;

public class ProtocolTest {

	private static final String serverLocation = "http://localhost/openrdf";

	private static final String repositoryID = "mem-rdf";

	private static final String repositoryLocation = serverLocation + "/" + REPOSITORIES + "/" + repositoryID;

	@Test
	public void testGetProtocolLocation() {
		String result = getProtocolLocation(serverLocation);
		assertThat(result).isEqualTo(serverLocation + "/" + PROTOCOL);
	}

	@Test
	public void testGetConfigLocation() {
		String result = getConfigLocation(serverLocation);
		assertThat(result).isEqualTo(serverLocation + "/" + CONFIG);
	}

	@Test
	public void testGetRepositoriesLocation() {
		String result = getRepositoriesLocation(serverLocation);
		assertThat(result).isEqualTo(serverLocation + "/" + REPOSITORIES);
	}

	@Test
	public void testGetServerLocation() {
		String repositoryLocation = getRepositoryLocation(serverLocation, repositoryID);

		String result = getServerLocation(repositoryLocation);
		assertEquals(serverLocation, result);
	}

	@Test
	public void testGetRepositoryID() {
		String repositoryLocation = getRepositoryLocation(serverLocation, repositoryID);

		String result = getRepositoryID(repositoryLocation);
		assertEquals(repositoryID, result);
	}

	@Test
	public void testGetRepositoryLocation() {
		String result = getRepositoryLocation(serverLocation, repositoryID);
		assertThat(result).isEqualTo(repositoryLocation);
	}

	@Test
	public void testGetRepositoryConfigLocation() {
		String result = getRepositoryConfigLocation(repositoryLocation);
		assertThat(result).isEqualTo(repositoryLocation + "/" + CONFIG);
	}

	@Test
	public void testGetContextsLocation() {
		String result = getContextsLocation(repositoryLocation);
		assertThat(result).isEqualTo(repositoryLocation + "/" + CONTEXTS);
	}

	@Test
	public void testGetNamespacesLocation() {
		String result = getNamespacesLocation(repositoryLocation);
		assertThat(result).isEqualTo(repositoryLocation + "/" + NAMESPACES);
	}

	@Test
	public void testEncodeValueRoundtrip() {
		final ValueFactory vf = SimpleValueFactory.getInstance();
		IRI uri = vf.createIRI("http://example.org/foo-bar");

		String encodedUri = Protocol.encodeValue(uri);
		IRI decodedUri = (IRI) Protocol.decodeValue(encodedUri, vf);

		assertEquals(uri, decodedUri);

		BNode bnode = vf.createBNode("foo-bar-1");
		String encodedBnode = Protocol.encodeValue(bnode);

		BNode decodedNode = (BNode) Protocol.decodeValue(encodedBnode, vf);
		assertEquals(bnode, decodedNode);

	}
}
