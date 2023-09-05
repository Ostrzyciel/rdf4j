/*******************************************************************************
 * Copyright (c) 2023 Eclipse RDF4J contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Distribution License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 ******************************************************************************/

package org.eclipse.rdf4j.sail.shacl.ast.planNodes;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.evaluation.util.ValueComparator;
import org.eclipse.rdf4j.sail.SailConnection;
import org.eclipse.rdf4j.sail.shacl.ast.SparqlFragment;
import org.eclipse.rdf4j.sail.shacl.ast.SparqlQueryParserCache;
import org.eclipse.rdf4j.sail.shacl.ast.StatementMatcher;
import org.eclipse.rdf4j.sail.shacl.ast.constraintcomponents.ConstraintComponent;

import com.google.common.collect.Sets;

/**
 *
 * @author Håvard Ottestad
 */
abstract class AbstractPairwisePlanNode implements PlanNode {

	private final SailConnection connection;
	private final Resource[] dataGraph;
	private final IRI predicate;
	private final StatementMatcher.Variable<Resource> subject;
	private final String query;
	private final Dataset dataset;
	private final StatementMatcher.Variable<Value> object;
	private final PlanNode parent;
	private ValidationExecutionLogger validationExecutionLogger;

	public AbstractPairwisePlanNode(SailConnection connection, Resource[] dataGraph, PlanNode parent,
			IRI predicate, StatementMatcher.Variable<Resource> subject, StatementMatcher.Variable<Value> object,
			SparqlFragment targetQueryFragment) {
		this.parent = parent;
		this.connection = connection;
		assert this.connection != null;
		this.dataGraph = dataGraph;
		this.predicate = predicate;
		this.subject = subject;
		this.object = object;
		if (targetQueryFragment != null) {
			this.query = "select * where {\n" + targetQueryFragment.getFragment() + "\n}";
		} else {
			this.query = null;
		}
		this.dataset = PlanNodeHelper.asDefaultGraphDataset(dataGraph);

	}

	private Set<Value> getMismatchedValues(ValidationTuple t) {

		Resource target = ((Resource) t.getActiveTarget());

		Set<Value> valuesByPath;
		Set<Value> valuesByPredicate;

		if (this.query == null) {
			valuesByPath = Set.of(target);
		} else {
			TupleExpr tupleExpr = SparqlQueryParserCache.get(query);

			try (Stream<? extends BindingSet> stream = connection
					.evaluate(tupleExpr, dataset, new SingletonBindingSet(subject.getName(), target), true)
					.stream()) {
				valuesByPath = stream.map(bindingSet -> bindingSet.getValue(object.getName()))
						.collect(Collectors.toSet());
			}
		}

		try (Stream<? extends Statement> stream = connection.getStatements(target, predicate, null, false, dataGraph)
				.stream()) {
			valuesByPredicate = stream.map(Statement::getObject).collect(Collectors.toSet());
		}

		return getInvalidValues(valuesByPath, valuesByPredicate);
	}

	abstract Set<Value> getInvalidValues(Set<Value> valuesByPath, Set<Value> valuesByPredicate);

	@Override
	public CloseableIteration<? extends ValidationTuple> iterator() {

		return new LoggingCloseableIteration(this, validationExecutionLogger) {

			private CloseableIteration<? extends ValidationTuple> parentIterator;

			private Iterator<ValidationTuple> nextIterator = null;
			private final ValueComparator valueComparator = new ValueComparator();

			@Override
			protected void init() {
				parentIterator = parent.iterator();
			}

			private void populateNextIterato() {
				if (nextIterator != null && nextIterator.hasNext()) {
					return;
				}

				while (parentIterator.hasNext() && (nextIterator == null || !nextIterator.hasNext())) {
					ValidationTuple next = parentIterator.next();
					Set<Value> mismatchedValues = getMismatchedValues(next);
					if (!mismatchedValues.isEmpty()) {
						nextIterator = Arrays.stream(mismatchedValues.toArray(new Value[0]))
								.sorted(valueComparator)
								.map(value -> {
									if (next.getScope() == ConstraintComponent.Scope.propertyShape) {
										return next.setValue(value);
									} else {
										return next.shiftToPropertyShapeScope(value);
									}
								})
								.iterator();
						return;
					}
				}

			}

			@Override
			protected ValidationTuple loggingNext() {
				populateNextIterato();

				return nextIterator.next();
			}

			@Override
			protected boolean localHasNext() {
				populateNextIterato();
				return nextIterator != null && nextIterator.hasNext();
			}

			@Override
			protected void localClose() {
				parentIterator.close();
			}
		};

	}

	@Override
	public int depth() {
		return parent.depth() + 1;
	}

	@Override
	public void getPlanAsGraphvizDot(StringBuilder stringBuilder) {

	}

	@Override
	public String getId() {
		return System.identityHashCode(this) + "CheckEqualsValuesBasedOnPathAndPredicate";
	}

	@Override
	public void receiveLogger(ValidationExecutionLogger validationExecutionLogger) {
		this.validationExecutionLogger = validationExecutionLogger;
		parent.receiveLogger(validationExecutionLogger);
	}

	@Override
	public boolean producesSorted() {
		return parent.producesSorted();
	}

	@Override
	public boolean requiresSorted() {
		return false;
	}
}
