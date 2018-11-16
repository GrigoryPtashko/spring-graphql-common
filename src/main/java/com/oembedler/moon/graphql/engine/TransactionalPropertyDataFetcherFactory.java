package com.oembedler.moon.graphql.engine;

import graphql.schema.PropertyDataFetcher;

public interface TransactionalPropertyDataFetcherFactory {
  PropertyDataFetcher createTransactionalPropertyDataFetcher(String fieldName);
}
