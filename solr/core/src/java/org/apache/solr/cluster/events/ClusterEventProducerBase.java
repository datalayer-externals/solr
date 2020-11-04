package org.apache.solr.cluster.events;

import org.apache.solr.common.util.IOUtils;
import org.apache.solr.core.CoreContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Base class for implementing {@link ClusterEventProducer}.
 */
public abstract class ClusterEventProducerBase implements ClusterEventProducer {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  protected final Map<ClusterEvent.EventType, Set<ClusterEventListener>> listeners = new ConcurrentHashMap<>();
  protected volatile State state = State.STOPPED;
  protected final CoreContainer cc;

  protected ClusterEventProducerBase(CoreContainer cc) {
    this.cc = cc;
  }

  @Override
  public void registerListener(ClusterEventListener listener, ClusterEvent.EventType... eventTypes) {
    if (eventTypes == null || eventTypes.length == 0) {
      eventTypes = ClusterEvent.EventType.values();
    }
    for (ClusterEvent.EventType type : eventTypes) {
      if (!getSupportedEventTypes().contains(type)) {
        log.warn("event type {} not supported yet.", type);
        continue;
      }
      // to avoid removing no-longer empty set on race in unregister
      synchronized (listeners) {
        listeners.computeIfAbsent(type, t -> ConcurrentHashMap.newKeySet())
            .add(listener);
      }
    }
  }

  @Override
  public void unregisterListener(ClusterEventListener listener, ClusterEvent.EventType... eventTypes) {
    if (eventTypes == null || eventTypes.length == 0) {
      eventTypes = ClusterEvent.EventType.values();
    }
    synchronized (listeners) {
      for (ClusterEvent.EventType type : eventTypes) {
        Set<ClusterEventListener> perType = listeners.get(type);
        if (perType != null) {
          perType.remove(listener);
          if (perType.isEmpty()) {
            listeners.remove(type);
          }
        }
      }
    }
  }

  @Override
  public State getState() {
    return state;
  }

  @Override
  public void close() throws IOException {
    synchronized (listeners) {
      listeners.values().forEach(listenerSet ->
          listenerSet.forEach(listener -> IOUtils.closeQuietly(listener)));
    }
  }

  public abstract Set<ClusterEvent.EventType> getSupportedEventTypes();

  protected void fireEvent(ClusterEvent event) {
    synchronized (listeners) {
      listeners.getOrDefault(event.getType(), Collections.emptySet())
          .forEach(listener -> {
            if (log.isDebugEnabled()) {
              log.debug("--- firing event {} to {}", event, listener);
            }
            listener.onEvent(event);
          });
    }
  }
}