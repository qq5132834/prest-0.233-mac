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

import com.facebook.presto.jdbc.QueryStats;
import com.facebook.presto.sql.parser.ParsingOptions;
import com.facebook.presto.sql.tree.Identifier;

import java.util.function.Consumer;
import java.util.function.Function;

import static com.facebook.presto.sql.parser.ParsingOptions.DecimalLiteralTreatment.AS_DOUBLE;
import static com.google.common.base.Functions.identity;

public class VerifierUtil
{
    private VerifierUtil()
    {
    }

    public static final ParsingOptions PARSING_OPTIONS = ParsingOptions.builder().setDecimalLiteralTreatment(AS_DOUBLE).build();

    public static Identifier delimitedIdentifier(String name)
    {
        return new Identifier(name, true);
    }

    public static void runWithQueryStatsConsumer(Callable<QueryStats> callable, Consumer<QueryStats> queryStatsConsumer)
    {
        callWithQueryStatsConsumer(callable, identity(), queryStatsConsumer);
    }

    public static <V> QueryResult<V> callWithQueryStatsConsumer(Callable<QueryResult<V>> callable, Consumer<QueryStats> queryStatsConsumer)
    {
        return callWithQueryStatsConsumer(callable, QueryResult::getQueryStats, queryStatsConsumer);
    }

    private static <V> V callWithQueryStatsConsumer(Callable<V> callable, Function<V, QueryStats> queryStatsTransformer, Consumer<QueryStats> queryStatsConsumer)
    {
        try {
            V result = callable.call();
            queryStatsConsumer.accept(queryStatsTransformer.apply(result));
            return result;
        }
        catch (PrestoQueryException e) {
            e.getQueryStats().ifPresent(queryStatsConsumer);
            throw e;
        }
    }

    public interface Callable<V>
    {
        V call();
    }
}
