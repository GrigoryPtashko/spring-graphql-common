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

import com.oembedler.moon.graphql.GraphQLConstants;
import com.oembedler.moon.graphql.engine.GraphQLSchemaHolder;
import com.oembedler.moon.graphql.engine.dfs.GraphQLFieldDefinitionWrapper;
import graphql.ExecutionResult;
import graphql.execution.ExecutionContext;
import graphql.execution.ExecutionParameters;
import graphql.execution.ExecutionStrategy;
import graphql.execution.NonNullableFieldValidator;
import graphql.execution.TypeInfo;
import graphql.language.Field;
import graphql.language.OperationDefinition;
import graphql.schema.*;
import org.springframework.core.NestedRuntimeException;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import rx.Observable;
import rx.observables.MathObservable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Idea was borrowed from <a href="https://github.com/nfl/graphql-rxjava"></a>
 *
 * @author <a href="mailto:java.lang.RuntimeException@gmail.com">oEmbedler Inc.</a>
 */
abstract class GraphQLAbstractRxExecutionStrategy extends ExecutionStrategy {

    public static final Double NODE_SCORE = 1.0;

    protected final GraphQLSchemaHolder graphQLSchemaHolder;
    protected final int maxQueryDepth;
    protected final int maxQueryComplexity;

    public GraphQLAbstractRxExecutionStrategy(GraphQLSchemaHolder graphQLSchemaHolder, int maxQueryDepth, int maxQueryComplexity) {
        this.graphQLSchemaHolder = graphQLSchemaHolder;
        this.maxQueryDepth = maxQueryDepth;
        this.maxQueryComplexity = maxQueryComplexity;
    }

    @Override
    public ExecutionResult execute(
        ExecutionContext executionContext, ExecutionParameters parameters) {

        GraphQLExecutionContext graphQLExecutionContext = wrapIfAny(executionContext);
        if (isCurrentDepthLimitExceeded(graphQLExecutionContext)) {
            return null;
        }

        return doExecute(updateContext(graphQLExecutionContext), parameters);
    }

    public abstract ExecutionResult doExecute(ExecutionContext executionContext, ExecutionParameters parameters);

    protected GraphQLExecutionContext wrapIfAny(ExecutionContext executionContext) {
        if (executionContext instanceof GraphQLExecutionContext) {
            return (GraphQLExecutionContext) executionContext;
        } else {
            int currentDepth =
                executionContext.getOperationDefinition().getOperation() == OperationDefinition.Operation.MUTATION ||
                    executionContext.getOperationDefinition().getOperation() == OperationDefinition.Operation.SUBSCRIPTION ? 1 : 0;
            return new GraphQLExecutionContext(executionContext, currentDepth);
        }
    }

    protected GraphQLExecutionContext updateContext(GraphQLExecutionContext currentExecutionContext) {
        return new GraphQLExecutionContext(currentExecutionContext.getDelegate(), currentExecutionContext.getCurrentDepth() + 1);
    }

    protected boolean isCurrentDepthLimitExceeded(GraphQLExecutionContext executionContext) {
        int currentDepth = executionContext.getCurrentDepth();
        return maxQueryDepth > 0 && currentDepth > maxQueryDepth;
    }

    protected Observable<Double> calculateFieldComplexity(ExecutionContext executionContext, ExecutionParameters parameters, List<Field> fields, Observable<Double> childScore) {
        GraphQLObjectType type = parameters.typeInfo().castType(GraphQLObjectType.class);
        return childScore.flatMap(aDouble -> {
            Observable<Double> result = Observable.just(aDouble + NODE_SCORE);
            GraphQLFieldDefinition fieldDef = getFieldDef(executionContext.getGraphQLSchema(), type, fields.get(0));
            if (fieldDef != null) {
                GraphQLFieldDefinitionWrapper graphQLFieldDefinitionWrapper = getGraphQLFieldDefinitionWrapper(fieldDef);
                if (graphQLFieldDefinitionWrapper != null) {
                    Expression expression = graphQLFieldDefinitionWrapper.getComplexitySpelExpression();
                    if (expression != null) {
                        Map<String, Object> argumentValues = valuesResolver.getArgumentValues(fieldDef.getArguments(), fields.get(0).getArguments(), executionContext.getVariables());
                        StandardEvaluationContext context = new StandardEvaluationContext();
                        context.setVariable(GraphQLConstants.EXECUTION_COMPLEXITY_CHILD_SCORE, aDouble);
                        if (argumentValues != null)
                            context.setVariables(argumentValues);
                        result = Observable.just(expression.getValue(context, Double.class));
                    }
                }
            }
            return addComplexityCheckObservable(executionContext, result);
        });
    }

    protected GraphQLFieldDefinitionWrapper getGraphQLFieldDefinitionWrapper(GraphQLFieldDefinition fieldDef) {
        return graphQLSchemaHolder.getFieldDefinitionResolverMap().get(fieldDef);
    }

    protected Observable<Double> addComplexityCheckObservable(ExecutionContext executionContext, Observable<Double> fieldComplexity) {
        if (executionContext instanceof GraphQLExecutionContext) {
            return fieldComplexity.flatMap(complexity -> {
                if (maxQueryComplexity > 0 && complexity > maxQueryComplexity)
                    throw new QueryComplexityLimitExceededRuntimeException("Query complexity limit exceeded. Current [" + complexity + "]. Limit [" + maxQueryComplexity + "]");
                return Observable.just(complexity);
            });
        }
        return fieldComplexity;
    }

    @Override
    protected ExecutionResult completeValue(
        ExecutionContext executionContext, ExecutionParameters parameters,
        List<Field> fields) {

        Object result = parameters.source();
        if (result instanceof Observable) {
            return new GraphQLRxExecutionResult(((Observable<?>) result).map(r -> super.completeValue(executionContext, parameters, fields)), null);
        }

        return super.completeValue(executionContext, parameters, fields);
    }

    @Override
    protected ExecutionResult completeValueForEnum(GraphQLEnumType enumType, ExecutionParameters parameters, Object result) {
        Object serialized = enumType.getCoercing().serialize(result);
        serialized = parameters.nonNullFieldValidator().validate(serialized);

        return new GraphQLRxExecutionResult(Observable.just(serialized), Observable.just(null), Observable.just(0.0));
    }

    @Override
    protected ExecutionResult completeValueForScalar(GraphQLScalarType scalarType, ExecutionParameters parameters, Object result) {
        Object serialized = scalarType.getCoercing().serialize(result);
        //6.6.1 http://facebook.github.io/graphql/#sec-Field-entries
        if (serialized instanceof Double && ((Double) serialized).isNaN()) {
            serialized = null;
        }
        serialized = parameters.nonNullFieldValidator().validate(serialized);

        return new GraphQLRxExecutionResult(Observable.just(serialized), Observable.just(null), Observable.just(0.0));
    }

    @Override
    protected ExecutionResult completeValueForList(
        ExecutionContext executionContext, ExecutionParameters parameters,
        List<Field> fields, Iterable<Object> result) {

        TypeInfo typeInfo = parameters.typeInfo();
        GraphQLList fieldType = typeInfo.castType(GraphQLList.class);
        TypeInfo wrappedTypeInfo = typeInfo.asType(fieldType.getWrappedType());
        NonNullableFieldValidator nonNullableFieldValidator = new NonNullableFieldValidator(executionContext, wrappedTypeInfo);
        List<Object> resultAsList = new ArrayList<>();
        result.forEach(resultAsList::add);
        Observable<List<ListTuple>> cachedObservable =
            Observable
                .from(
                    IntStream.range(0, resultAsList.size())
                        .mapToObj(idx -> new ListTuple(idx, resultAsList.get(idx), null))
                        .toArray(ListTuple[]::new))
                .flatMap(tuple -> {
                    ExecutionParameters newParameters = ExecutionParameters
                        .newParameters()
                        .typeInfo(wrappedTypeInfo)
                        .fields(parameters.fields())
                        .nonNullFieldValidator(nonNullableFieldValidator)
                        .source(tuple.result)
                        .build();
                    ExecutionResult executionResult = completeValue(executionContext, newParameters, fields);
                    if (executionResult instanceof GraphQLRxExecutionResult) {
                        return Observable.zip(Observable.just(tuple.index),
                            ((GraphQLRxExecutionResult) executionResult).getDataObservable(),
                            ((GraphQLRxExecutionResult) executionResult).getComplexityObservable(),
                            ListTuple::new);
                    }

                    return Observable.just(new ListTuple(tuple.index, executionResult.getData(), Observable.just(1.0)));
                })
                .toList()
                .cache();

        Observable<?> resultObservable =
            cachedObservable
                .map(listTuples ->
                    listTuples.stream()
                        .sorted(Comparator.comparingInt(x -> x.index))
                        .map(x -> x.result)
                        .collect(Collectors.toList()));

        Observable<?> complexityObservable = cachedObservable
                .map(listTuples -> {
                    return listTuples.stream()
                            .sorted(Comparator.comparingInt(x -> x.index))
                            .map(x -> x.complexity)
                            .collect(Collectors.toList());
                })
                .flatMap(combined -> Observable.from(combined));

        return new GraphQLRxExecutionResult(resultObservable, null, MathObservable.sumDouble((Observable<Double>) complexityObservable));
    }

    private class ListTuple {
        public int index;
        public Object result;
        public Object complexity;

        public ListTuple(int index, Object result, Object complexity) {
            this.index = index;
            this.result = result;
            this.complexity = complexity;
        }
    }


    public static class QueryComplexityLimitExceededRuntimeException extends NestedRuntimeException {
        public QueryComplexityLimitExceededRuntimeException(String msg) {
            super(msg);
        }
    }
}
