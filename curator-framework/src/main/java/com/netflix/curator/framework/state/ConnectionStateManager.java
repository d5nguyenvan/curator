/*
 *
 *  Copyright 2011 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */

package com.netflix.curator.framework.state;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.netflix.curator.framework.CuratorFramework;
import com.netflix.curator.framework.listen.ListenerContainer;
import java.io.Closeable;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Used internally to manage connection state
 */
public class ConnectionStateManager implements Closeable
{
    private static final int   QUEUE_SIZE;
    static
    {
        int         size = 25;
        String      property = System.getProperty("ConnectionStateManagerSize", null);
        if ( property != null )
        {
            try
            {
                size = Integer.parseInt(property);
            }
            catch ( NumberFormatException ignore )
            {
                // ignore
            }
        }
        QUEUE_SIZE = size;
    }

    private final BlockingQueue<ConnectionState>                eventQueue = new ArrayBlockingQueue<ConnectionState>(QUEUE_SIZE);
    private final CuratorFramework                              client;
    private final ExecutorService                               service;
    private final ListenerContainer<ConnectionStateListener>    listeners = new ListenerContainer<ConnectionStateListener>();
    private final AtomicReference<ConnectionState>              currentState = new AtomicReference<ConnectionState>(ConnectionState.RECONNECTED);

    /**
     * @param client the client
     */
    public ConnectionStateManager(CuratorFramework client)
    {
        this.client = client;
        service = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("ConnectionStateManager-%d").build());
    }

    /**
     * Start the manager
     */
    public void     start()
    {
        Preconditions.checkState(!service.isShutdown());

        service.submit
        (
            new Callable<Object>()
            {
                @Override
                public Object call() throws Exception
                {
                    processEvents();
                    return null;
                }
            }
        );
    }

    @Override
    public void close()
    {
        Preconditions.checkState(!service.isShutdown());

        service.shutdownNow();
        listeners.clear();
    }

    /**
     * Return the listenable
     *
     * @return listenable
     */
    public ListenerContainer<ConnectionStateListener> getListenable()
    {
        return listeners;
    }

    /**
     * Post a state change. If the manager is already in that state the change
     * is ignored. Otherwise the change is queued for listeners.
     *
     * @param newState new state
     */
    public void addStateChange(ConnectionState newState)
    {
        Preconditions.checkState(!service.isShutdown());

        if ( currentState.getAndSet(newState) == newState )
        {
            return;
        }

        client.getZookeeperClient().getLog().info("State change: " + newState);
        while ( !eventQueue.offer(newState) )
        {
            eventQueue.poll();
            client.getZookeeperClient().getLog().warn("ConnectionStateManager queue full - dropping events to make room");
        }
    }

    private void processEvents()
    {
        try
        {
            while ( !Thread.currentThread().isInterrupted() )
            {
                final ConnectionState    newState = eventQueue.take();

                if ( listeners.size() == 0 )
                {
                    client.getZookeeperClient().getLog().warn("There are no ConnectionStateListeners registered.");
                }

                listeners.forEach
                (
                    new Function<ConnectionStateListener, Void>()
                    {
                        @Override
                        public Void apply(ConnectionStateListener listener)
                        {
                            listener.stateChanged(client, newState);
                            return null;
                        }
                    }
                );
            }
        }
        catch ( InterruptedException e )
        {
            Thread.currentThread().interrupt();
        }
    }
}
