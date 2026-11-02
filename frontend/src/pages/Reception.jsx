import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../services/api';
import { useLive } from '../hooks/useLive';
import DeptSelect from '../components/DeptSelect';
import { Card, Empty, ErrorBox, Loading, mins, Stat, StatusBadge } from '../components/ui';

export function ReceptionDashboard() {
  const [dep, setDep] = useState('');
  const board = useLive(async () => {
    const [deps, doctors] = await Promise.all([api('/api/departments'), api('/api/doctors')]);
    const queues = await Promise.all(doctors.map((d) => api(`/api/queues/doctor/${d.doctorId}`).catch(() => [])));
    return doctors.map((d, i) => ({ d, q: queues[i], deps: d.departmentIds.map((id) => deps.find((x) => x.departmentId === id)?.name || id) }));
  }, [], 6000);
  const cap = useLive(() => (dep ? api(`/api/queues/capacity/${dep}`) : Promise.resolve(null)), dep ? [`/topic/department/${dep}`] : [], 10000);
  const rows = board.data || [];
  const totalWaiting = rows.reduce((a, r) => a + r.q.filter((x) => !x.status.match(/CALLED|IN_CONSULTATION/)).length, 0);
  const c = cap.data;
  return (
    <>
      <h1>Reception</h1><ErrorBox message={board.error || cap.error} />
      <div className="stats">
        <Stat label="Patients waiting (all doctors)" value={totalWaiting} />
        <Stat label="Doctors online" value={`${rows.filter((r) => r.d.status === 'ONLINE').length} / ${rows.length}`} />
        <Stat label="Being served now" value={rows.filter((r) => r.q.some((x) => x.status === 'IN_CONSULTATION')).length} />
      </div>
      <div className="row"><Link className="btn primary" to="/receptionist/register-walkin">Register walk-in</Link>
        <Link className="btn ghost" to="/receptionist/checkin">Check in by token</Link><Link className="btn ghost" to="/receptionist/queue">Live queue</Link></div>
      <Card title="Doctors right now">
        {board.loading ? <Loading /> : rows.length === 0 ? <Empty text="No doctors yet." /> : (
          <div className="scroll"><table><thead><tr><th>Doctor</th><th>Department</th><th>Status</th><th>Waiting</th><th>Serving</th></tr></thead>
            <tbody>{rows.map(({ d, q, deps }) => {
              const cur = q.find((x) => x.status === 'IN_CONSULTATION' || x.status === 'CALLED');
              return <tr key={d.doctorId}><td>{d.name}</td><td>{deps.join(', ')}</td><td>{d.status.replace('_', ' ')}</td>
                <td>{q.filter((x) => x !== cur).length}</td><td>{cur ? `${cur.token} (${cur.status.replace('_', ' ')})` : '—'}</td></tr>; })}</tbody></table></div>)}
      </Card>
      <Card title="Today's capacity">
        <DeptSelect value={dep} onChange={setDep} />
        {c ? (<div className="stats">
          <Stat label="Daily capacity" value={c.dailyCapacity} /><Stat label="Normal capacity" value={c.normalCapacity} />
          <Stat label="Normal slots left" value={c.remainingNormal} /><Stat label="Emergency reserve" value={c.emergencyReserve} />
          <Stat label="Emergency used" value={c.emergencyUsed} /><Stat label="Reserve left" value={c.remainingReserve} /></div>) : <Empty text="Select a department." />}
      </Card>
    </>
  );
}

export function RegisterWalkin() {
  const [dep, setDep] = useState(''); const [name, setName] = useState(''); const [doctorId, setDoctorId] = useState('');
  const [emergency, setEmergency] = useState(false);
  const [busy, setBusy] = useState(false); const [err, setErr] = useState(''); const [result, setResult] = useState(null);
  const recs = useLive(() => (dep ? api(`/api/recommendations/doctors?departmentId=${dep}`) : Promise.resolve(null)), dep ? [`/topic/department/${dep}`] : [], 10000);
  const options = (recs.data || []).filter((o) => o.available);
  const chosen = doctorId || options.find((o) => o.recommended)?.doctorId || '';   // optional preference; falls back to the recommended doctor

  const submit = async (e) => {
    e.preventDefault(); setBusy(true); setErr(''); setResult(null);
    try {
      const entry = await api('/api/queues/join', { method: 'POST', body: { doctorId: chosen, departmentId: dep, patientName: name, emergency, idempotencyKey: crypto.randomUUID() } });
      const pos = await api(`/api/queues/${entry.queueEntryId}/position`);
      setResult({ entry, pos, doctor: options.find((o) => o.doctorId === chosen)?.doctorName });
      setName(''); setEmergency(false);
    } catch (x) { setErr(x.message); } finally { setBusy(false); }
  };

  return (
    <>
      <h1>Register walk-in</h1>
      <form className="card form" onSubmit={submit}>
        <ErrorBox message={err} />
        <DeptSelect value={dep} onChange={(v) => { setDep(v); setDoctorId(''); }} />
        <label className="field">Patient name<input required value={name} onChange={(e) => setName(e.target.value)} /></label>
        <label className="field">Doctor (optional, defaults to the recommended doctor)
          <select value={doctorId} onChange={(e) => setDoctorId(e.target.value)} disabled={!dep}>
            <option value="">Recommended based on predicted waiting time</option>
            {options.map((o) => <option key={o.doctorId} value={o.doctorId}>{o.doctorName}: {mins(o.predictedWaitMinutes)} ({o.patientsAhead} waiting)</option>)}
          </select>
        </label>
        <label className="check"><input type="checkbox" checked={emergency} onChange={(e) => setEmergency(e.target.checked)} /> Emergency (uses reserved capacity)</label>
        {dep && !recs.loading && options.length === 0 && <Empty text="No doctors are currently available." />}
        <button className="btn primary" disabled={busy || !chosen || !name}>{busy ? 'Adding...' : 'Add to Queue'}</button>
      </form>
      {result && (
        <Card title="Patient registered">
          <div className="token">{result.entry.token}</div>
          <p>Doctor: {result.doctor}</p><p>Position: {result.pos.position}</p><p>Estimated wait: {mins(result.pos.baselineWaitMinutes)}</p>
        </Card>)}
    </>
  );
}

export function LiveQueue() {
  const [dep, setDep] = useState(''); const [msg, setMsg] = useState('');
  const { data, loading, error, reload } = useLive(async () => {
    if (!dep) return null;
    const doctors = (await api('/api/doctors')).filter((d) => d.departmentIds.includes(dep));
    return Promise.all(doctors.map(async (d) => ({ doctor: d, queue: await api(`/api/queues/doctor/${d.doctorId}`) })));
  }, dep ? [`/topic/department/${dep}`] : [], 8000);

  const act = async (id, action) => {
    setMsg('');
    try { await api(`/api/queues/${id}/${action}`, { method: 'POST' }); await reload(); } catch (e) { setMsg(e.message); }
  };
  return (
    <>
      <h1>Live queue</h1>
      <DeptSelect value={dep} onChange={setDep} />
      <ErrorBox message={error || msg} />
      {dep && loading && <Loading text="Loading queue..." />}
      {!dep && <Empty text="Select a department." />}
      {(data || []).map(({ doctor, queue }) => (
        <Card key={doctor.doctorId} title={`${doctor.name} · ${doctor.status.replace('_', ' ')}`}>
          {queue.length === 0 ? <Empty text="Queue is empty." /> : (
            <div className="scroll"><table><thead><tr><th>#</th><th>Token</th><th>Status</th><th>Est. wait</th><th>Actions</th></tr></thead>
              <tbody>{queue.map((q) => (
                <tr key={q.queueEntryId}><td>{q.position}</td><td>{q.token}</td><td><StatusBadge status={q.status} /></td><td>{mins(q.baselineWaitMinutes)}</td>
                  <td className="row">
                    {['WAITING', 'LATE'].includes(q.status) && <button className="btn small" onClick={() => act(q.queueEntryId, 'check-in')}>Check in</button>}
                    {['WAITING', 'CHECKED_IN', 'CALLED', 'LATE'].includes(q.status) && <button className="btn small danger" onClick={() => act(q.queueEntryId, 'no-show')}>No-show</button>}
                    {q.status === 'WAITING' && <button className="btn small ghost" onClick={() => act(q.queueEntryId, 'cancel')}>Cancel</button>}
                  </td></tr>))}</tbody></table></div>)}
        </Card>))}
    </>
  );
}

export function CheckIn() {
  const [token, setToken] = useState(''); const [found, setFound] = useState(null);
  const [err, setErr] = useState(''); const [msg, setMsg] = useState(''); const [busy, setBusy] = useState(false);
  const find = async (e) => {
    e?.preventDefault(); setBusy(true); setErr(''); setMsg(''); setFound(null);
    try { setFound(await api(`/api/queues/lookup?token=${encodeURIComponent(token.trim())}`)); }
    catch (x) { setErr(x.message); } finally { setBusy(false); }
  };
  const act = async (action, ok) => {
    setBusy(true); setErr(''); setMsg('');
    try { await api(`/api/queues/${found.queueEntryId}/${action}`, { method: 'POST' }); setMsg(ok); setFound(await api(`/api/queues/lookup?token=${encodeURIComponent(found.token)}`)); }
    catch (x) { setErr(x.message); } finally { setBusy(false); }
  };
  return (
    <>
      <h1>Check in by token</h1>
      <form className="card form" onSubmit={find}>
        <label className="field">Token (for example GM-004)<input value={token} onChange={(e) => setToken(e.target.value)} placeholder="GM-004" required /></label>
        <button className="btn primary" disabled={busy || !token}>Find</button>
      </form>
      <ErrorBox message={err} />{msg && <div className="alert ok">{msg}</div>}
      {found && (
        <Card title={found.token}>
          <p>Patient: <b>{found.patientName}</b> · Doctor: {found.doctor} · {found.source === 'ONLINE' ? 'Online' : 'Walk-in'}</p>
          <StatusBadge status={found.status} />
          <div className="row">
            {['WAITING', 'LATE'].includes(found.status) && <button className="btn primary" disabled={busy} onClick={() => act('check-in', 'Checked in.')}>Check in</button>}
            {found.status === 'CALLED' && <button className="btn ghost" disabled={busy} onClick={() => act('late', 'Marked late. Patient keeps their place if still within the grace period.')}>Patient arrived late</button>}
            {['WAITING', 'CHECKED_IN', 'CALLED', 'LATE'].includes(found.status) && <button className="btn danger" disabled={busy} onClick={() => act('no-show', 'Marked no-show.')}>No-show</button>}
          </div>
        </Card>)}
    </>
  );
}
