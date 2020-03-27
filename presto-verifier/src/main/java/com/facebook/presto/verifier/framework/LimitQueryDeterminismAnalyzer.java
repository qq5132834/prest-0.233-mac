/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.verifier.framework;

import com.facebook.presto.sql.tree.CreateTableAsSelect;
import com.facebook.presto.sql.tree.FunctionCall;
import com.facebook.presto.sql.tree.Insert;
import com.facebook.presto.sql.tree.LongLiteral;
import com.facebook.presto.sql.tree.QualifiedName;
import com.facebook.presto.sql.tree.Query;
import com.facebook.presto.sql.tree.QuerySpecification;
import com.facebook.presto.sql.tree.Select;
import com.facebook.presto.sql.tree.SingleColumn;
import com.facebook.presto.sql.tree.Statement;
import com.facebook.presto.sql.tree.TableSubquery;
import com.facebook.presto.sql.tree.With;
import com.facebook.presto.verifier.prestoaction.PrestoAction;
import com.google.common.collect.ImmutableList;

import java.util.Optional;

import static com.facebook.presto.sql.QueryUtil.simpleQuery;
import static com.facebook.presto.verifier.framework.LimitQueryDeterminismAnalysis.DETERMINISTIC;
import static com.facebook.presto.verifier.framework.LimitQueryDeterminismAnalysis.FAILED_DATA_CHANGED;
import static com.facebook.presto.verifier.framework.LimitQueryDeterminismAnalysis.NON_DETERMINISTIC;
import static com.facebook.presto.verifier.framework.LimitQueryDeterminismAnalysis.NOT_RUN;
import static com.facebook.presto.verifier.framework.QueryStage.DETERMINISM_ANALYSIS;
import static com.facebook.presto.verifier.framework.VerifierUtil.callWithQueryStatsConsumer;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Iterables.getOnlyElement;
import static java.lang.Long.parseLong;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;

public class LimitQueryDeterminismAnalyzer
{
    private final PrestoAction prestoAction;
    private final boolean enabled;

    private final Statement statement;
    private final long rowCount;
    private final VerificationContext verificationContext;

    public LimitQueryDeterminismAnalyzer(
            PrestoAction prestoAction,
            boolean enabled,
            Statement statement,
            long rowCount,
            VerificationContext verificationContext)
    {
        this.prestoAction = requireNonNull(prestoAction, "prestoAction is null");
        this.enabled = enabled;
        this.statement = requireNonNull(statement, "statement is null");
        checkArgument(rowCount >= 0, "rowCount is negative: %s", rowCount);
        this.rowCount = rowCount;
        this.verificationContext = requireNonNull(verificationContext, "verificationContext is null");
    }

    public LimitQueryDeterminismAnalysis analyze()
    {
        LimitQueryDeterminismAnalysis analysis = analyzeInternal();
        verificationContext.setLimitQueryAnalysis(analysis);
        return analysis;
    }

    private LimitQueryDeterminismAnalysis analyzeInternal()
    {
        if (!enabled) {
            return NOT_RUN;
        }

        Query query;

        // A query is rewritten to either an Insert or a CreateTableAsSelect
        if (statement instanceof Insert) {
            query = ((Insert) statement).getQuery();
        }
        else if (statement instanceof CreateTableAsSelect) {
            query = ((CreateTableAsSelect) statement).getQuery();
        }
        else {
            return NOT_RUN;
        }

        // Flatten TableSubquery
        if (query.getQueryBody() instanceof TableSubquery) {
            Optional<With> with = query.getWith();
            while (query.getQueryBody() instanceof TableSubquery) {
                // ORDER BY and LIMIT must be empty according to syntax
                if (query.getOrderBy().isPresent() || query.getLimit().isPresent()) {
                    return NOT_RUN;
                }
                query = ((TableSubquery) query.getQueryBody()).getQuery();
                // WITH must be empty according to syntax
                if (query.getWith().isPresent()) {
                    return NOT_RUN;
                }
            }
            query = new Query(with, query.getQueryBody(), query.getOrderBy(), query.getLimit());
        }

        if (query.getQueryBody() instanceof QuerySpecification) {
            return analyzeQuerySpecification(query.getWith(), (QuerySpecification) query.getQueryBody());
        }
        return analyzeQuery(query);
    }

    private LimitQueryDeterminismAnalysis analyzeQuery(Query query)
    {
        if (query.getOrderBy().isPresent() || !query.getLimit().isPresent()) {
            return NOT_RUN;
        }
        if (isLimitAll(query.getLimit().get())) {
            return NOT_RUN;
        }
        long limit = parseLong(query.getLimit().get());
        Optional<String> newLimit = Optional.of(Long.toString(limit + 1));
        Query newLimitQuery = new Query(query.getWith(), query.getQueryBody(), Optional.empty(), newLimit);
        return analyzeLimitNoOrderBy(newLimitQuery, limit);
    }

    private LimitQueryDeterminismAnalysis analyzeQuerySpecification(Optional<With> with, QuerySpecification querySpecification)
    {
        if (querySpecification.getOrderBy().isPresent() || !querySpecification.getLimit().isPresent()) {
            return NOT_RUN;
        }
        if (isLimitAll(querySpecification.getLimit().get())) {
            return NOT_RUN;
        }
        long limit = parseLong(querySpecification.getLimit().get());
        Optional<String> newLimit = Optional.of(Long.toString(limit + 1));
        Query newLimitQuery = new Query(
                with,
                new QuerySpecification(
                        querySpecification.getSelect(),
                        querySpecification.getFrom(),
                        querySpecification.getWhere(),
                        querySpecification.getGroupBy(),
                        querySpecification.getHaving(),
                        Optional.empty(),
                        newLimit),
                Optional.empty(),
                Optional.empty());
        return analyzeLimitNoOrderBy(newLimitQuery, limit);
    }

    private LimitQueryDeterminismAnalysis analyzeLimitNoOrderBy(Query newLimitQuery, long limit)
    {
        Query rowCountQuery = simpleQuery(
                new Select(false, ImmutableList.of(new SingleColumn(new FunctionCall(QualifiedName.of("count"), ImmutableList.of(new LongLiteral("1")))))),
                new TableSubquery(newLimitQuery));

        QueryResult<Long> result = callWithQueryStatsConsumer(
                () -> prestoAction.execute(rowCountQuery, DETERMINISM_ANALYSIS, resultSet -> resultSet.getLong(1)),
                stats -> verificationContext.setLimitQueryAnalysisQueryId(stats.getQueryId()));

        long rowCountHigherLimit = getOnlyElement(result.getResults());
        if (rowCountHigherLimit == rowCount) {
            return DETERMINISTIC;
        }
        if (rowCount >= limit && rowCountHigherLimit > rowCount) {
            return NON_DETERMINISTIC;
        }
        return FAILED_DATA_CHANGED;
    }

    private static boolean isLimitAll(String limitClause)
    {
        return limitClause.toLowerCase(ENGLISH).equals("all");
    }
}
