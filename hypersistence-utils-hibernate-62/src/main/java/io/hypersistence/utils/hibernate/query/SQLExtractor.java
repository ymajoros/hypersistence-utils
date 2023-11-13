package io.hypersistence.utils.hibernate.query;

import io.hypersistence.utils.hibernate.util.ReflectionUtils;
import jakarta.persistence.Parameter;
import jakarta.persistence.Query;
import org.hibernate.query.spi.DomainQueryExecutionContext;
import org.hibernate.query.spi.QueryImplementor;
import org.hibernate.query.spi.QueryInterpretationCache;
import org.hibernate.query.spi.QueryParameterBindings;
import org.hibernate.query.spi.SelectQueryPlan;
import org.hibernate.query.sqm.internal.ConcreteSqmSelectQueryPlan;
import org.hibernate.query.sqm.internal.DomainParameterXref;
import org.hibernate.query.sqm.internal.QuerySqmImpl;
import org.hibernate.query.sqm.internal.SqmInterpretationsKey;
import org.hibernate.query.sqm.tree.select.SqmSelectStatement;
import org.hibernate.sql.exec.spi.JdbcOperationQuerySelect;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The {@link SQLExtractor} allows you to extract the
 * underlying SQL query generated by a JPQL or JPA Criteria API query.
 * <p>
 * For more details about how to use it, check out <a href="https://vladmihalcea.com/get-sql-from-jpql-or-criteria/">this article</a> on <a href="https://vladmihalcea.com/">vladmihalcea.com</a>.
 *
 * @author Vlad Mihalcea
 * @since 2.9.11
 */
public class SQLExtractor {

    protected SQLExtractor() {
    }

    /**
     * Get the underlying SQL generated by the provided JPA query.
     *
     * @param query JPA query
     * @return the underlying SQL generated by the provided JPA query
     */
    public static String from(Query query) {
        return getSqmQueryOptional(query)
            .map(SQLExtractor::getSQLFromSqmQuery)
            .orElseGet(() -> ReflectionUtils.invokeMethod(query, "getQueryString"));
    }

    private static String getSQLFromSqmQuery(QuerySqmImpl<?> querySqm) {
        QueryInterpretationCache.Key cacheKey = SqmInterpretationsKey.createInterpretationsKey(querySqm);
        Supplier<SelectQueryPlan<Object>> buildSelectQueryPlan = () -> ReflectionUtils.invokeMethod(querySqm, "buildSelectQueryPlan");
        SelectQueryPlan<Object> plan = cacheKey != null ? ((QueryImplementor<?>) querySqm).getSession().getFactory().getQueryEngine()
            .getInterpretationCache()
            .resolveSelectQueryPlan(cacheKey, buildSelectQueryPlan) :
            buildSelectQueryPlan.get();
        if (plan instanceof ConcreteSqmSelectQueryPlan) {
            ConcreteSqmSelectQueryPlan<?> selectQueryPlan = (ConcreteSqmSelectQueryPlan<?>) plan;
            Object cacheableSqmInterpretation = ReflectionUtils.getFieldValueOrNull(selectQueryPlan, "cacheableSqmInterpretation");
            if (cacheableSqmInterpretation == null) {
                cacheableSqmInterpretation = ReflectionUtils.invokeStaticMethod(
                    ReflectionUtils.getMethod(
                        ConcreteSqmSelectQueryPlan.class,
                        "buildCacheableSqmInterpretation",
                        SqmSelectStatement.class,
                        DomainParameterXref.class,
                        DomainQueryExecutionContext.class
                    ),
                    ReflectionUtils.getFieldValueOrNull(selectQueryPlan, "sqm"),
                    ReflectionUtils.getFieldValueOrNull(selectQueryPlan, "domainParameterXref"),
                    querySqm
                );
            }
            if (cacheableSqmInterpretation != null) {
                JdbcOperationQuerySelect jdbcSelect = ReflectionUtils.getFieldValueOrNull(cacheableSqmInterpretation, "jdbcSelect");
                if (jdbcSelect != null) {
                    return jdbcSelect.getSqlString();
                }
            }
        }
        return querySqm.getQueryString();
    }

    public static List<Object> getSQLParameterValues(Query query) {
        return getSqmQueryOptional(query)
            .map(SQLExtractor::getParametersFromInternalQuerySqm)
            .orElseGet(() -> getSQLParametersFromJPAQuery(query));
    }

    /**
     * Retrieves the parameters from the internal query SQM.
     *
     * @param querySqm the internal query SQM object
     * @return a list of parameter values
     */
    private static List<Object> getParametersFromInternalQuerySqm(QuerySqmImpl<?> querySqm) {
        List<Object> parameterValues = new ArrayList<>();

        QueryParameterBindings parameterBindings = querySqm.getParameterBindings();
        parameterBindings.visitBindings((queryParameterImplementor, queryParameterBinding) -> {
            Object value = queryParameterBinding.getBindValue();
            parameterValues.add(value);
        });

        return parameterValues;
    }

    /**
     * Get parameters from JPA query without any magic or Hibernate implementation tricks. Order is probably lost in current Hibernate versions.
     *
     * @param query
     * @return
     */
    private static List<Object> getSQLParametersFromJPAQuery(Query query) {
        return query.getParameters()
            .stream()
            .map(Parameter::getPosition)
            .map(query::getParameter)
            .collect(Collectors.toList());
    }


    /**
     * Get the unproxied hibernate query underlying the provided query object.
     *
     * @param query JPA query
     * @return the unproxied Hibernate query, or original query if there is no proxy, or null if it's not an Hibernate query of required type
     */
    private static Optional<QuerySqmImpl<?>> getSqmQueryOptional(Query query) {
        Query unwrappedQuery = query.unwrap(Query.class);
        if (unwrappedQuery instanceof QuerySqmImpl) {
            QuerySqmImpl<?> querySqm = (QuerySqmImpl<?>) unwrappedQuery;
            return Optional.of(querySqm);
        }
        return Optional.empty();
    }
}
