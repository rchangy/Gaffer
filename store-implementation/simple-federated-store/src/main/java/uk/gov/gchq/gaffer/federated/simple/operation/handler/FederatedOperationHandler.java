/*
 * Copyright 2024 Crown Copyright
 *
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

package uk.gov.gchq.gaffer.federated.simple.operation.handler;

import uk.gov.gchq.gaffer.federated.simple.FederatedStore;
import uk.gov.gchq.gaffer.graph.GraphSerialisable;
import uk.gov.gchq.gaffer.operation.Operation;
import uk.gov.gchq.gaffer.operation.OperationChain;
import uk.gov.gchq.gaffer.operation.OperationException;
import uk.gov.gchq.gaffer.operation.io.Output;
import uk.gov.gchq.gaffer.store.Context;
import uk.gov.gchq.gaffer.store.Store;
import uk.gov.gchq.gaffer.store.operation.handler.OperationChainHandler;
import uk.gov.gchq.gaffer.store.operation.handler.OperationHandler;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Main default handler for federated operations. Handles delegation to selected
 * graphs and will sub class the operation to a {@link FederatedOutputHandler}
 * if provided operation has output so that it is merged.
 */
public class FederatedOperationHandler<P extends Operation> implements OperationHandler<P> {

    /**
     * The operation option for the Graph IDs that an operation should be
     * executed on, will take preference over the short variant of this option.
     */
    public static final String OPT_GRAPH_IDS = "gaffer.federatedstore.operation.graphIds";

    /**
     * The short version of the operation option for the Graph IDs that an
     * operation should be executed on.
     */
    public static final String OPT_SHORT_GRAPH_IDS = "federated.graphIds";

    /**
     * The boolean operation option to specify if element merging should be applied or not.
     */
    public static final String OPT_AGGREGATE_ELEMENTS = "federated.aggregateElements";

    @Override
    public Object doOperation(final P operation, final Context context, final Store store) throws OperationException {

        // Check inside operation chains in case there are operations that don't require running on sub graphs
        if (operation instanceof OperationChain) {
            Set<Class<? extends Operation>> storeSpecificOps = ((FederatedStore) store).getStoreSpecificOperations();
            List<Class<? extends Operation>> chainOps = ((OperationChain<?>) operation).flatten().stream()
                .map(Operation::getClass)
                .collect(Collectors.toList());
            // If all the operations in the chain can be handled by the store then execute them
            if (storeSpecificOps.containsAll(chainOps)) {
                return new OperationChainHandler<>(store.getOperationChainValidator(), store.getOperationChainOptimisers())
                    .doOperation((OperationChain<Object>) operation, context, store);
            }

            // Check if we have a mix as that is an issue
            // It's better to keep federated and non federated separate so error and report back
            if (!Collections.disjoint(storeSpecificOps, chainOps)) {
                throw new OperationException(
                    "Chain contains standard Operations alongside federated store specific Operations."
                        + " Please submit each type separately.");
            }
        }

        // If the operation has output wrap and return using sub class handler
        if (operation instanceof Output) {
            return new FederatedOutputHandler<>().doOperation((Output) operation, context, store);
        }

        List<GraphSerialisable> graphsToExecute = getGraphsToExecuteOn((FederatedStore) store, operation);
        // No-op
        if (graphsToExecute.isEmpty()) {
            return null;
        }

        // Execute the operation chain on each graph
        for (final GraphSerialisable gs : graphsToExecute) {
            gs.getGraph().execute(operation, context.getUser());
        }

        // Assume no output, we've already checked above
        return null;
    }


    /**
     * Extract the graph IDs from the operation and process the option.
     * Will default to the store configured graph IDs if no option present.
     * <p>
     * Returned list will be ordered alphabetically based on graph ID for
     * predicability.
     *
     * @param store The federated store.
     * @param operation The operation to execute.
     * @return List of {@link GraphSerialisable}s to execute on.
     */
    protected List<GraphSerialisable> getGraphsToExecuteOn(final FederatedStore store, final Operation operation) {
        List<String> graphIds = store.getDefaultGraphIds();
        List<GraphSerialisable> graphsToExecute = new LinkedList<>();
        // If user specified graph IDs for this chain parse as comma separated list
        if (operation.containsOption(OPT_GRAPH_IDS)) {
            graphIds = Arrays.asList(operation.getOption(OPT_GRAPH_IDS).split(","));
        } else if (operation.containsOption(OPT_SHORT_GRAPH_IDS)) {
            graphIds = Arrays.asList(operation.getOption(OPT_SHORT_GRAPH_IDS).split(","));
        }

        // Get the corresponding graph serialisable
        for (final String id : graphIds) {
            graphsToExecute.add(store.getGraph(id));
        }

        // Keep graphs sorted so results returned are predictable between runs
        Collections.sort(graphsToExecute, (g1, g2) -> g1.getGraphId().compareTo(g2.getGraphId()));

        return graphsToExecute;
    }

}
