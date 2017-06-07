/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 oEmbedler Inc. and Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 *  documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 *  rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit
 *  persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
 * BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.oembedler.moon.graphql.engine.execute;

import com.oembedler.moon.graphql.engine.GraphQLSchemaHolder;
import graphql.ExecutionResult;
import graphql.GraphQLException;
import graphql.execution.*;
import graphql.language.Document;
import graphql.language.Field;
import graphql.language.OperationDefinition;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static graphql.execution.TypeInfo.newTypeInfo;
import static graphql.language.OperationDefinition.Operation.MUTATION;
import static graphql.language.OperationDefinition.Operation.QUERY;
import static graphql.language.OperationDefinition.Operation.SUBSCRIPTION;

/**
 * @author <a href="mailto:java.lang.RuntimeException@gmail.com">oEmbedler Inc.</a>
 */
class RxExecution {

    private FieldCollector fieldCollector = new FieldCollector();
    private final ExecutionStrategy strategy;
    private final GraphQLSchemaHolder graphQLSchemaHolder;
    private final int maxQueryDepth;
    private final int maxQueryComplexity;

    public RxExecution(
        GraphQLSchemaHolder graphQLSchemaHolder, int maxQueryDepth,
        int maxQueryComplexity, ExecutionStrategy strategy) {

        this.strategy = strategy;
        this.graphQLSchemaHolder = graphQLSchemaHolder;
        this.maxQueryDepth = maxQueryDepth;
        this.maxQueryComplexity = maxQueryComplexity;
    }

    public ExecutionResult execute(
        GraphQLSchema graphQLSchema, Object context, Document document,
        String operationName, Map<String, Object> args) {

        Object root = Collections.emptyMap();
        ExecutionContextBuilder executionContextBuilder =
            new ExecutionContextBuilder(new ValuesResolver(), null);
        ExecutionContext executionContext =
            executionContextBuilder
                .executionId(ExecutionId.from("1"))
                .build(
                    graphQLSchema, strategy, strategy, strategy, context, root,
                    document, operationName, args);

        return
            executeOperation(
                executionContext, root, executionContext.getOperationDefinition());
    }

    private GraphQLObjectType getOperationRootType(
        GraphQLSchema graphQLSchema, OperationDefinition operationDefinition) {

        if (operationDefinition.getOperation() == MUTATION) {
            return graphQLSchema.getMutationType();

        } else if (operationDefinition.getOperation() == SUBSCRIPTION) {
            return graphQLSchema.getSubscriptionType();

        } else if (operationDefinition.getOperation() == QUERY) {
            return graphQLSchema.getQueryType();

        } else {
            throw new GraphQLException();
        }
    }

    private ExecutionResult executeOperation(
            ExecutionContext executionContext,
            Object root,
            OperationDefinition operationDefinition) {

        GraphQLObjectType operationRootType =
            getOperationRootType(
                executionContext.getGraphQLSchema(),
                executionContext.getOperationDefinition());

        FieldCollectorParameters fcParameters = FieldCollectorParameters
            .newParameters(
                graphQLSchemaHolder.getGraphQLSchema(), operationRootType)
            .build();
        Map<String, List<Field>> fields =
            fieldCollector.collectFields(
                fcParameters, operationDefinition.getSelectionSet());

        ExecutionStrategyParameters parameters =
            ExecutionStrategyParameters.newParameters()
                .typeInfo(newTypeInfo().type(operationRootType))
                .source(root)
                .fields(fields)
                .build();
        if (operationDefinition.getOperation() == MUTATION ||
            operationDefinition.getOperation() == SUBSCRIPTION) {
            return
                new GraphQLDefaultRxExecutionStrategy(
                    graphQLSchemaHolder, maxQueryDepth, maxQueryComplexity)
                    .execute(executionContext, parameters);
        } else {
            return strategy.execute(executionContext, parameters);
        }
    }
}
