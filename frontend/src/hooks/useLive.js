import { useCallback, useEffect, useRef, useState } from 'react';
import { Client } from '@stomp/stompjs';
import { BASE } from '../services/api';

/**
 * Live data: fetch now, refetch whenever the backend publishes an event on any of `topics`
 * (STOMP over WebSocket, published after each committed queue transaction), and poll as a safety net.
 * The server remains the single source of truth; the UI never computes positions itself.
 */
export function useLive(fetcher, topics = [], pollMs = 8000) {
  const [state, setState] = useState({ data: null, loading: true, error: null, updatedAt: null });
  const fetchRef = useRef(fetcher);
  fetchRef.current = fetcher;

  const load = useCallback(async () => {
    try {
      const data = await fetchRef.current();
      setState({ data, loading: false, error: null, updatedAt: new Date() });
    } catch (e) {
      setState((s) => ({ ...s, loading: false, error: e.message }));
    }
  }, []);

  const key = topics.join('|');
  useEffect(() => {
    load();
    const t = setInterval(load, pollMs);
    let client;
    if (topics.length) {
      client = new Client({ brokerURL: BASE.replace(/^http/, 'ws') + '/ws', reconnectDelay: 5000 });
      client.onConnect = () => topics.forEach((tp) => client.subscribe(tp, load));
      client.activate();
    }
    return () => { clearInterval(t); client?.deactivate(); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, pollMs, load]);

  return { ...state, reload: load };
}
