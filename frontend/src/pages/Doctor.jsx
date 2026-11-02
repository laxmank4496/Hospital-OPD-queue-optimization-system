import { useEffect, useState } from 'react';
import { api } from '../services/api';
import { useLive } from '../hooks/useLive';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../components/Toast';
import { BarCard, LineCard } from '../components/charts';
import { Card, Empty, ErrorBox, Loading, mins, Stat, StatusBadge } from '../components/ui';

/** Live queue + doctor record, refreshed on every backend event for this doctor. */
function useDoctor() {
  const { me } = useAuth(); const id = me.doctorId; const toast = useToast();
  const [busy, setBusy] = useState(false);
  const live = useLive(async () => {
    const [queue, doctors] = await Promise.all([api(`/api/queues/doctor/${id}`), api('/api/doctors')]);
    return { queue, doctor: doctors.find((d) => d.doctorId === id) };
  }, [`/topic/doctor/${id}`], 8000);

  const run = async (fn, okMsg) => {
    setBusy(true);
    try { const r = await fn(); if (okMsg) toast(okMsg(r), 'ok'); await live.reload(); return r; }
    catch (e) { toast(e.message, 'danger'); } finally { setBusy(false); }
  };
  const queue = live.data?.queue || []; const doctor = live.data?.doctor;
  const current = queue.find((q) => q.status === 'IN_CONSULTATION' || q.status === 'CALLED');
  const waiting = queue.filter((q) => q !== current);
  return {
    id, ...live, queue, doctor, current, waiting, busy,
    setStatus: (s) => run(() => api(`/api/doctors/${id}/availability`, { method: 'PUT', body: { status: s } }), () => `You are now ${s.replace('_', ' ')}.`),
    callNext: () => run(() => api(`/api/queues/${id}/call-next`, { method: 'POST' }), (r) => (r ? `Called ${r.token}.` : 'No checked-in patients are waiting.')),
    act: (entryId, action, msg) => run(() => api(`/api/queues/${entryId}/${action}`, { method: 'POST' }), () => msg),
  };
}

function Availability({ d }) {
  return (
    <Card title="Availability">
      <div className="row">
        {[['ONLINE', 'Online'], ['ON_BREAK', 'On break'], ['OFFLINE', 'Offline']].map(([s, label]) => (
          <button key={s} disabled={d.busy} className={`btn ${d.doctor?.status === s ? 'primary' : 'ghost'}`} onClick={() => d.setStatus(s)}>{label}</button>))}
      </div>
      <p className="muted small">Patients can only join your queue while you are Online. Patients already in your queue stay in it when you go on break or offline.</p>
    </Card>
  );
}

function CurrentPatient({ d }) {
  const c = d.current;
  return (
    <Card title="Current patient">
      {c ? (<>
        <div className="token">{c.token}</div><StatusBadge status={c.status} />
        <div className="row">
          {c.status === 'CALLED' && <>
            <button className="btn primary" disabled={d.busy} onClick={() => d.act(c.queueEntryId, 'start-consultation', 'Consultation started.')}>Start Consultation</button>
            <button className="btn ghost" disabled={d.busy} onClick={() => d.act(c.queueEntryId, 'late', 'Marked late.')}>Patient is late</button>
            <button className="btn danger" disabled={d.busy} onClick={() => d.act(c.queueEntryId, 'no-show', 'Marked no-show.')}>No-show</button></>}
          {c.status === 'IN_CONSULTATION' &&
            <button className="btn success" disabled={d.busy} onClick={() => d.act(c.queueEntryId, 'complete-consultation', 'Consultation completed. Queue advanced.')}>Complete Consultation</button>}
        </div>
      </>) : (<>
        <Empty text="No patient with you right now." />
        <button className="btn primary" disabled={d.busy || d.doctor?.status !== 'ONLINE'} onClick={d.callNext}>Call Next Patient</button>
        {d.doctor?.status !== 'ONLINE' && <p className="muted small">Go online to call patients.</p>}
      </>)}
    </Card>
  );
}

function QueueTable({ d, withActions }) {
  if (d.waiting.length === 0) return <Empty text="Nobody is waiting." />;
  return (
    <div className="scroll"><table><thead><tr><th>#</th><th>Token</th><th>Status</th><th>Est. wait</th>{withActions && <th />}</tr></thead>
      <tbody>{d.waiting.map((q) => (
        <tr key={q.queueEntryId}><td>{q.position}</td><td>{q.token}</td><td><StatusBadge status={q.status} /></td><td>{mins(q.baselineWaitMinutes)}</td>
          {withActions && <td>{['WAITING', 'CHECKED_IN', 'LATE'].includes(q.status) && <button className="btn small danger" disabled={d.busy} onClick={() => d.act(q.queueEntryId, 'no-show', 'Marked no-show.')}>No-show</button>}</td>}</tr>))}</tbody></table></div>
  );
}

export function DoctorDashboard() {
  const d = useDoctor();
  if (!d.id) return <ErrorBox message="This account is not linked to a doctor profile." />;
  if (d.loading) return <Loading text="Loading your queue..." />;
  return (
    <>
      <h1>{d.doctor?.name || 'Doctor dashboard'}</h1><ErrorBox message={d.error} />
      <div className="stats">
        <Stat label="Status" value={d.doctor?.status?.replace('_', ' ')} />
        <Stat label="Patients waiting" value={d.waiting.length} />
        <Stat label="Avg consultation" value={d.doctor?.averageConsultationDuration ? `${d.doctor.averageConsultationDuration.toFixed(1)} min` : 'No data yet'} />
        <Stat label="Next patient wait" value={d.waiting[0] ? mins(d.waiting[0].baselineWaitMinutes) : '—'} />
      </div>
      <div className="two-col"><div><CurrentPatient d={d} /><Availability d={d} /></div>
        <Card title="Upcoming queue"><QueueTable d={d} withActions /></Card></div>
    </>
  );
}

export function DoctorQueue() {
  const d = useDoctor();
  if (d.loading) return <Loading />;
  return (
    <>
      <h1>Current queue</h1><ErrorBox message={d.error} />
      <Card title={`Patients waiting: ${d.waiting.length}`}><QueueTable d={d} withActions /></Card>
    </>
  );
}

export function DoctorConsultation() {
  const d = useDoctor();
  const [now, setNow] = useState(Date.now());
  const [started, setStarted] = useState(null);
  useEffect(() => { const t = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(t); }, []);
  const cur = d.current;
  useEffect(() => {
    if (cur?.status === 'IN_CONSULTATION') api(`/api/queues/${cur.queueEntryId}`).then((e) => setStarted(e.consultationStartedAt)).catch(() => {});
    else setStarted(null);
  }, [cur?.queueEntryId, cur?.status]);
  if (d.loading) return <Loading />;
  const sec = started ? Math.max(0, Math.floor((now - started) / 1000)) : 0;
  const avg = d.doctor?.averageConsultationDuration;
  return (
    <>
      <h1>Consultation</h1>
      <CurrentPatient d={d} />
      {cur?.status === 'IN_CONSULTATION' && (
        <Card title="Consultation timer">
          <div className="token">{String(Math.floor(sec / 60)).padStart(2, '0')}:{String(sec % 60).padStart(2, '0')}</div>
          <p className="muted">{avg ? `Your average is ${avg.toFixed(1)} min.` : 'No average yet.'} The duration is recorded when you click Complete.</p>
        </Card>)}
      <Card title="Next in line">{d.waiting.length ? <p>Next: <b>{d.waiting[0].token}</b> ({d.waiting[0].status.replace('_', ' ')})</p> : <Empty text="Nobody is waiting." />}</Card>
    </>
  );
}

export function DoctorAvailability() {
  const d = useDoctor();
  if (d.loading) return <Loading />;
  return (<><h1>Availability</h1><Availability d={d} /></>);
}

export function DoctorStats() {
  const { data, loading, error } = useLive(() => api('/api/analytics/dashboard?days=7'), [], 20000);
  if (loading) return <Loading />;
  const t = data?.today;
  return (
    <>
      <h1>Statistics</h1><ErrorBox message={error} />
      {t && (<div className="stats">
        <Stat label="Seen today" value={t.served} /><Stat label="Avg consultation" value={t.avgDuration ? `${t.avgDuration} min` : '—'} />
        <Stat label="Avg patient wait" value={t.avgWait ? `${t.avgWait} min` : '—'} /><Stat label="No-shows today" value={t.noShow} /></div>)}
      <div className="grid2">
        <BarCard title="Patient wait by hour (min)" data={data?.waitByHour} x="hour" bars={[{ key: 'avgWait', name: 'Avg wait' }]} />
        <LineCard title="Last 7 days" data={data?.trend} x="date" lines={[{ key: 'patients', name: 'Patients' }, { key: 'avgDuration', name: 'Avg consult (min)' }]} />
      </div>
    </>
  );
}
