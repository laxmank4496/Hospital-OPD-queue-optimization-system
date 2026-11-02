import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { sendPasswordResetEmail, updateProfile } from 'firebase/auth';
import { auth } from '../services/firebase';
import { api } from '../services/api';
import { useLive } from '../hooks/useLive';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../components/Toast';
import { Card, Empty, ErrorBox, Loading, mins, Stat, StatusBadge } from '../components/ui';

const STEPS = ['Joined', 'Checked in', 'Called', 'In consultation', 'Done'];
const STEP_OF = { WAITING: 0, CHECKED_IN: 1, LATE: 1, CALLED: 2, IN_CONSULTATION: 3, COMPLETED: 4 };
const fmt = (ms) => (ms ? new Date(ms).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' }) : '—');

function Stepper({ status }) {
  const cur = STEP_OF[status] ?? 0;
  return (
    <ol className="stepper" aria-label="Progress">
      {STEPS.map((s, i) => <li key={s} className={i < cur ? 'done' : i === cur ? 'now' : ''}><span>{i + 1}</span>{s}</li>)}
    </ol>
  );
}

/** Live card for the signed-in patient's active entry. Shared by Dashboard and My Queue. */
function ActiveQueue() {
  const toast = useToast();
  const [busy, setBusy] = useState(false); const [actionErr, setActionErr] = useState('');
  const [doctorId, setDoctorId] = useState(null);
  const prev = useRef(null);
  const { data, loading, error, reload, updatedAt } = useLive(async () => {
    const entry = await api('/api/queues/mine');
    setDoctorId(entry ? entry.doctorId : null);
    if (!entry) return null;
    const [pos, doctors, deps] = await Promise.all([api(`/api/queues/${entry.queueEntryId}/position`), api('/api/doctors'), api('/api/departments')]);
    return { entry, pos, doctor: doctors.find((d) => d.doctorId === entry.doctorId), department: deps.find((d) => d.departmentId === entry.departmentId)?.name };
  }, doctorId ? [`/topic/doctor/${doctorId}`] : [], 6000);

  // notify when something important changes (no page refresh needed)
  const status = data?.entry?.status; const position = data?.pos?.position;
  useEffect(() => {
    if (!status) { prev.current = null; return; }
    const p = prev.current;
    if (p && p.status !== status) {
      if (status === 'CALLED') { toast('You have been called. Please go to the doctor\'s room.', 'warn'); document.title = '🔔 You are called!'; }
      else if (status === 'IN_CONSULTATION') toast('Your consultation has started.', 'info');
      else if (status === 'NO_SHOW') toast('You were marked as no-show because you did not arrive in time.', 'danger');
    } else if (p && p.position > 1 && position === 1 && status !== 'CALLED') toast('You are next in line.', 'info');
    if (status !== 'CALLED') document.title = 'Smart OPD Queue';
    prev.current = { status, position };
  }, [status, position, toast]);

  const act = async (path) => {
    setBusy(true); setActionErr('');
    try { await api(path, { method: 'POST' }); await reload(); } catch (e) { setActionErr(e.message); } finally { setBusy(false); }
  };

  if (loading) return <Loading text="Loading your queue..." />;
  if (!data) return (
    <Card><Empty text="You are not in a queue right now." /><Link className="btn primary" to="/patient/find-doctor">Find a doctor</Link></Card>
  );
  const { entry, pos, doctor, department } = data;
  return (
    <>
      <ErrorBox message={error || actionErr} />
      <Card title={`${department || ''} · ${doctor?.name || ''}`} className="hero-card">
        <div className="row spread">
          <div><div className="muted">Your token</div><div className="token">{entry.token}</div></div>
          <div className="right"><StatusBadge status={entry.status} /><div className="muted small">{entry.source === 'ONLINE' ? 'Joined online' : 'Registered at desk'}</div></div>
        </div>
        <Stepper status={entry.status} />
        {entry.status === 'CALLED' && <div className="alert warn big-alert">You have been called. Please go to the doctor's room now.</div>}
        {entry.status === 'LATE' && <div className="alert warn">You are marked late. Check in at the desk to keep your place.</div>}
        <div className="stats">
          <Stat label="Queue position" value={pos.position} />
          <Stat label="Patients ahead" value={pos.patientsAhead} />
          <Stat label="Estimated waiting time" value={mins(pos.baselineWaitMinutes)} />
          <Stat label="Doctor status" value={doctor?.status === 'ONLINE' ? 'Available' : doctor?.status?.replace('_', ' ') || '—'} />
        </div>
        <p className="muted small">Estimate = patients ahead × this doctor's average consultation time ({doctor?.averageConsultationDuration ? doctor.averageConsultationDuration.toFixed(1) : '7'} min). It is not a guarantee.</p>
        <div className="row">
          {['WAITING', 'LATE'].includes(entry.status) &&
            <button className="btn primary" disabled={busy} onClick={() => act(`/api/queues/${entry.queueEntryId}/check-in`)}>Check In</button>}
          {entry.status === 'WAITING' &&
            <button className="btn danger" disabled={busy} onClick={() => window.confirm('Cancel your queue entry?') && act(`/api/queues/${entry.queueEntryId}/cancel`)}>Cancel Queue</button>}
        </div>
        <ul className="muted small notes">
          <li>Check-in status: {['WAITING'].includes(entry.status) ? 'not checked in yet' : 'checked in'}</li>
          <li>Joined: {fmt(entry.joinedAt)}</li>
          <li>Last updated: {updatedAt?.toLocaleTimeString()} (updates automatically)</li>
        </ul>
      </Card>
    </>
  );
}

export function MyQueue() { return (<><h1>My queue</h1><ActiveQueue /></>); }

export function PatientDashboard() {
  const { user } = useAuth();
  const hist = useLive(() => api('/api/history/mine'), [], 20000);
  const h = hist.data || [];
  const done = h.filter((x) => x.status === 'COMPLETED');
  const waits = done.map((x) => x.waitedMinutes).filter((x) => x != null);
  return (
    <>
      <h1>Hello, {user.displayName || 'Patient'}</h1>
      <ActiveQueue />
      <div className="stats">
        <Stat label="Visits completed" value={done.length} />
        <Stat label="Average wait" value={waits.length ? mins(waits.reduce((a, b) => a + b, 0) / waits.length) : '—'} />
        <Stat label="Cancelled" value={h.filter((x) => x.status === 'CANCELLED').length} />
      </div>
      <Card title="How it works">
        <ol className="how"><li>Find a doctor and compare waiting times.</li><li>Choose your doctor and join the queue. You get a token.</li>
          <li>Check in when you arrive, then watch your position update live.</li><li>When you are called, go to the doctor's room.</li></ol>
      </Card>
    </>
  );
}

export function FindDoctor() {
  const nav = useNavigate();
  const [dep, setDep] = useState(''); const [joining, setJoining] = useState(''); const [err, setErr] = useState('');
  const deps = useLive(() => api('/api/departments'), [], 60000);
  const recs = useLive(() => (dep ? api(`/api/recommendations/doctors?departmentId=${dep}`) : Promise.resolve(null)),
    dep ? [`/topic/department/${dep}`] : [], 10000);
  const cap = useLive(() => (dep ? api('/api/doctors') : Promise.resolve(null)), [], 20000);
  const avgOf = (id) => cap.data?.find((d) => d.doctorId === id)?.averageConsultationDuration;

  const join = async (doctorId, name) => {
    if (!window.confirm(`Join the queue for ${name}?`)) return;
    setJoining(doctorId); setErr('');
    try {
      await api('/api/queues/join', { method: 'POST', body: { doctorId, departmentId: dep, idempotencyKey: crypto.randomUUID() } });
      nav('/patient/my-queue');
    } catch (e) { setErr(e.message); recs.reload(); } finally { setJoining(''); }
  };

  return (
    <>
      <h1>Find a doctor</h1>
      <label className="field">Department
        <select value={dep} onChange={(e) => setDep(e.target.value)}>
          <option value="">Select a department...</option>
          {(deps.data || []).map((d) => <option key={d.departmentId} value={d.departmentId}>{d.name}</option>)}
        </select>
      </label>
      <ErrorBox message={err || recs.error} />
      {!dep && <Empty text="Choose a department to see available doctors and their waiting times." />}
      {dep && recs.loading && <Loading text="Loading available doctors..." />}
      {dep && recs.data && recs.data.length === 0 && <Empty text="No doctors are listed for this department." />}
      {dep && recs.data && recs.data.length > 0 && !recs.data.some((o) => o.available) && <div className="alert warn">No doctors are currently available. Please check again shortly.</div>}
      <div className="grid3">
        {(recs.data || []).map((o) => (
          <article key={o.doctorId} className={`card doc ${o.recommended ? 'rec' : ''} ${o.available ? '' : 'off'}`}>
            {o.recommended && <div className="badge green">★ Recommended based on predicted waiting time</div>}
            <h3>{o.doctorName}</h3>
            <p className="muted small"><span className={`dot ${o.available ? 'on' : 'off'}`} /> {o.available ? 'Available' : (o.reason || 'Unavailable')}</p>
            <div className="stats two">
              <Stat label="Patients waiting" value={o.patientsAhead} />
              <Stat label="Estimated wait" value={o.available ? mins(o.predictedWaitMinutes) : '—'} />
            </div>
            {avgOf(o.doctorId) ? <p className="muted small">Average consultation: {avgOf(o.doctorId).toFixed(1)} min</p> : <p className="muted small">No consultation history yet. Using a default of 7 min.</p>}
            <button className="btn primary" disabled={!o.available || !!joining} onClick={() => join(o.doctorId, o.doctorName)}>
              {joining === o.doctorId ? 'Joining...' : 'Select Doctor'}
            </button>
          </article>
        ))}
      </div>
      {dep && <p className="muted small">The recommendation is only a suggestion based on the shortest estimated wait. You choose your doctor. Estimates are not guarantees.</p>}
    </>
  );
}

export function PatientHistory() {
  const { data, loading, error } = useLive(() => api('/api/history/mine'), [], 20000);
  if (loading) return <Loading text="Loading your history..." />;
  return (
    <>
      <h1>Queue history</h1><ErrorBox message={error} />
      <Card>{!data || data.length === 0 ? <Empty text="You have no queue history yet." /> : (
        <div className="scroll"><table><thead><tr><th>Date</th><th>Token</th><th>Department</th><th>Doctor</th><th>Status</th><th>Waited</th></tr></thead>
          <tbody>{data.map((h) => <tr key={h.queueEntryId}><td>{fmt(h.joinedAt)}</td><td>{h.token}</td><td>{h.department}</td><td>{h.doctor}</td>
            <td><StatusBadge status={h.status} /></td><td>{h.waitedMinutes != null ? `${h.waitedMinutes} min` : '—'}</td></tr>)}</tbody></table></div>)}</Card>
    </>
  );
}

export function Profile() {
  const { user, me } = useAuth(); const toast = useToast();
  const [name, setName] = useState(user.displayName || ''); const [busy, setBusy] = useState(false);
  const saveName = async (e) => {
    e.preventDefault(); setBusy(true);
    try { await updateProfile(auth.currentUser, { displayName: name.trim() }); toast('Name updated. It shows after your next page load.', 'ok'); }
    catch { toast('Could not update your name.', 'danger'); } finally { setBusy(false); }
  };
  const reset = async () => {
    try { await sendPasswordResetEmail(auth, user.email); toast(`Password reset email sent to ${user.email}.`, 'ok'); }
    catch { toast('Could not send the reset email.', 'danger'); }
  };
  return (
    <>
      <h1>Profile</h1>
      <form className="card form" onSubmit={saveName}>
        <label className="field">Full name<input value={name} onChange={(e) => setName(e.target.value)} required /></label>
        <label className="field">Email<input value={user.email} disabled /></label>
        <label className="field">Role<input value={me.role} disabled /></label>
        <div className="row"><button className="btn primary" disabled={busy}>Save</button><button type="button" className="btn ghost" onClick={reset}>Send password reset email</button></div>
      </form>
    </>
  );
}
