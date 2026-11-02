import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { useLive } from '../hooks/useLive';
import { api } from '../services/api';

/** Public lobby TV page. Tokens only: no login, no patient names. */
export default function QueueDisplay() {
  const { departmentId } = useParams();
  const [clock, setClock] = useState(new Date());
  useEffect(() => { const t = setInterval(() => setClock(new Date()), 1000); return () => clearInterval(t); }, []);
  const { data, error } = useLive(async () => {
    const [d, deps] = await Promise.all([api(`/api/public/queue-display/${departmentId}`), api('/api/public/departments')]);
    return { ...d, name: deps.find((x) => x.departmentId === departmentId)?.name || departmentId };
  }, [`/topic/department/${departmentId}`], 4000);
  const serving = data?.nowServing?.join('  ·  ');

  return (
    <div className="tv">
      <div className="tv-clock">{clock.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</div>
      <h1>{data?.name?.toUpperCase() || '...'}</h1>
      <h2>NOW SERVING</h2>
      <div key={serving} className="tv-token flash">{serving || '—'}</div>
      <h2>NEXT</h2>
      <div className="tv-next">{data?.next?.length ? data.next.join('   ') : '—'}</div>
      <p className="tv-count">Patients waiting: {data?.waiting ?? '—'}</p>
      <footer>{error ? 'Reconnecting...' : 'Please wait for your token to be called.'}</footer>
    </div>
  );
}
