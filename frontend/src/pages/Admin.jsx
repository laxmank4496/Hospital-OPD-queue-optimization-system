import { useEffect, useState } from 'react';
import { api, BASE } from '../services/api';
import { useLive } from '../hooks/useLive';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../components/Toast';
import DeptSelect from '../components/DeptSelect';
import { BarCard, LineCard, PieCard } from '../components/charts';
import { Card, Empty, ErrorBox, Loading, Stat } from '../components/ui';

const pct = (v) => `${v}%`;

export function AdminDashboard() {
  const { data, loading, error } = useLive(() => api('/api/analytics/dashboard?days=7'), [], 10000);
  if (loading) return <Loading text="Loading analytics..." />;
  if (error && !data) return <ErrorBox message={error} />;
  const t = data.today;
  return (
    <>
      <h1>Admin dashboard</h1><ErrorBox message={error} />
      {data.demoRecords > 0 && <div className="alert warn">These figures include {data.demoRecords} simulated demo record(s). Remove them in Demo Simulator.</div>}
      <div className="stats">
        <Stat label="Registered today" value={t.registered} /><Stat label="Patients served" value={t.served} />
        <Stat label="Currently waiting" value={t.waitingNow} /><Stat label="Average wait" value={t.served ? `${t.avgWait} min` : '—'} />
        <Stat label="Maximum wait" value={t.served ? `${t.maxWait} min` : '—'} /><Stat label="Doctors online" value={`${data.doctors.online} / ${data.doctors.total}`} />
        <Stat label="Doctor utilization" value={t.served ? pct(t.utilization) : '—'} /><Stat label="Emergency reserve used" value={`${data.emergency.used} / ${data.emergency.reserve}`} />
      </div>
      <div className="grid2">
        <BarCard title="Patients registered today, by department" data={data.byDepartment} x="department" bars={[{ key: 'patients', name: 'Patients' }]} />
        <LineCard title="Average waiting time by hour (min)" data={data.waitByHour} x="hour" lines={[{ key: 'avgWait', name: 'Avg wait' }]} />
        <BarCard title="Average consultation duration by doctor (min)" data={data.durationByDoctor} x="doctor" bars={[{ key: 'avgDuration', name: 'Minutes', color: '#7a4fd0' }]} />
        <BarCard title="Doctor utilization (%)" data={data.utilization} x="doctor" unit="%" bars={[{ key: 'utilization', name: 'Utilization', color: '#1e9e5a' }]} />
        <PieCard title="Completed vs cancelled vs no-show (today)" data={data.outcomes} />
        <LineCard title="Waiting-time trend (7 days)" data={data.trend} x="date" lines={[{ key: 'avgWait', name: 'Avg wait (min)' }, { key: 'patients', name: 'Patients' }]} />
      </div>
    </>
  );
}

export function AdminAnalytics() {
  const [days, setDays] = useState(7);
  const { data, loading, error } = useLive(() => api(`/api/analytics/dashboard?days=${days}`), [`days${days}`], 15000);
  if (loading) return <Loading />;
  if (error && !data) return <ErrorBox message={error} />;
  const f = data.fairness, t = data.today;
  return (
    <>
      <h1>Analytics and fairness</h1>
      <div className="row"><span className="muted">Trend range:</span>{[7, 14, 30].map((d) => <button key={d} className={`btn small ${days === d ? 'primary' : 'ghost'}`} onClick={() => setDays(d)}>{d} days</button>)}</div>
      <Card title="Queue performance (today)">
        <div className="stats">
          <Stat label="Average wait" value={t.served ? `${t.avgWait} min` : '—'} /><Stat label="Maximum wait" value={t.served ? `${t.maxWait} min` : '—'} />
          <Stat label="Patients served" value={t.served} /><Stat label="Cancellation rate" value={t.registered ? pct(t.cancellationRate) : '—'} />
          <Stat label="No-show rate" value={t.registered ? pct(t.noShowRate) : '—'} /><Stat label="Utilization" value={t.served ? pct(t.utilization) : '—'} />
        </div>
      </Card>
      <Card title="Fairness (today)">
        {f.patientsServed === 0 ? <Empty text="No completed consultations yet, so there is nothing to measure." /> : (
          <div className="stats">
            <Stat label="Average wait" value={`${f.averageWait} min`} /><Stat label="Maximum wait" value={`${f.maxWait} min`} />
            <Stat label="Wait variance" value={f.variance} /><Stat label="Fairness index (Jain)" value={f.jainIndex} />
            <Stat label={`Waited over ${f.delayedThresholdMinutes} min`} value={f.patientsDelayed} />
          </div>)}
        <p className="muted small">Fairness index: 1.0 means everyone waited about the same; lower values mean waits are uneven. It is calculated only from completed consultations.</p>
      </Card>
      <div className="grid2">
        <LineCard title={`Average wait per day (${days} days)`} data={data.trend} x="date" lines={[{ key: 'avgWait', name: 'Avg wait (min)' }]} />
        <BarCard title={`Patients served per day (${days} days)`} data={data.trend} x="date" bars={[{ key: 'patients', name: 'Patients', color: '#1e9e5a' }]} />
      </div>
      <Card title="Doctor utilization (today)">
        {data.utilization.length === 0 ? <Empty text="No consultations yet today." /> : (
          <div className="scroll"><table><thead><tr><th>Doctor</th><th>Busy minutes</th><th>Utilization</th></tr></thead>
            <tbody>{data.utilization.map((u) => <tr key={u.doctor}><td>{u.doctor}</td><td>{u.busyMinutes}</td><td>{u.utilization}%</td></tr>)}</tbody></table></div>)}
        <p className="muted small">Utilization = minutes in consultation ÷ minutes since the doctor's first consultation today.</p>
      </Card>
    </>
  );
}

function DepChecks({ deps, value, onChange }) {
  const toggle = (id) => onChange(value.includes(id) ? value.filter((x) => x !== id) : [...value, id]);
  return (
    <fieldset><legend>Departments</legend>
      {deps.map((d) => <label className="check" key={d.departmentId}><input type="checkbox" checked={value.includes(d.departmentId)} onChange={() => toggle(d.departmentId)} /> {d.name}</label>)}
    </fieldset>
  );
}

export function AdminDoctors() {
  const toast = useToast();
  const deps = useLive(() => api('/api/departments'), [], 60000);
  const docs = useLive(() => api('/api/doctors'), [], 10000);
  const blank = { name: '', departmentIds: [], email: '', password: '' };
  const [form, setForm] = useState(blank); const [editing, setEditing] = useState(null); const [busy, setBusy] = useState(false);
  const depName = (id) => deps.data?.find((d) => d.departmentId === id)?.name || id;

  const submit = async (e) => {
    e.preventDefault(); setBusy(true);
    try {
      if (editing) await api(`/api/doctors/${editing}`, { method: 'PUT', body: { name: form.name, departmentIds: form.departmentIds } });
      else await api('/api/doctors', { method: 'POST', body: { name: form.name, departmentIds: form.departmentIds, email: form.email || null, password: form.password || null } });
      toast(editing ? 'Doctor updated.' : 'Doctor added.', 'ok'); setForm(blank); setEditing(null); docs.reload();
    } catch (x) { toast(x.message, 'danger'); } finally { setBusy(false); }
  };
  const remove = async (d) => {
    if (!window.confirm(`Delete ${d.name}?`)) return;
    try { await api(`/api/doctors/${d.doctorId}`, { method: 'DELETE' }); toast('Doctor deleted.', 'ok'); docs.reload(); } catch (x) { toast(x.message, 'danger'); }
  };
  return (
    <>
      <h1>Doctors</h1><ErrorBox message={docs.error} />
      <form className="card form" onSubmit={submit}>
        <h3>{editing ? 'Edit doctor' : 'Add doctor'}</h3>
        <label className="field">Name<input required value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder="Dr. Name" /></label>
        <DepChecks deps={deps.data || []} value={form.departmentIds} onChange={(v) => setForm({ ...form, departmentIds: v })} />
        {!editing && (<>
          <label className="field">Login email (optional)<input type="email" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} /></label>
          <label className="field">Login password (8+ characters, needed if email given)<input type="password" minLength={8} value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} /></label></>)}
        <div className="row"><button className="btn primary" disabled={busy || !form.name || form.departmentIds.length === 0}>{editing ? 'Save changes' : 'Add doctor'}</button>
          {editing && <button type="button" className="btn ghost" onClick={() => { setEditing(null); setForm(blank); }}>Cancel</button>}</div>
      </form>
      <Card>{docs.loading ? <Loading /> : (docs.data || []).length === 0 ? <Empty text="No doctors yet." /> : (
        <div className="scroll"><table><thead><tr><th>Name</th><th>Departments</th><th>Status</th><th>Avg consult</th><th>Completed</th><th /></tr></thead>
          <tbody>{docs.data.map((d) => (
            <tr key={d.doctorId}><td>{d.name}</td><td>{d.departmentIds.map(depName).join(', ')}</td><td>{d.status.replace('_', ' ')}</td>
              <td>{d.averageConsultationDuration ? `${d.averageConsultationDuration.toFixed(1)} min` : '—'}</td><td>{d.consultationsCompleted}</td>
              <td className="row"><button className="btn small ghost" onClick={() => { setEditing(d.doctorId); setForm({ name: d.name, departmentIds: d.departmentIds, email: '', password: '' }); window.scrollTo(0, 0); }}>Edit</button>
                <button className="btn small danger" onClick={() => remove(d)}>Delete</button></td></tr>))}</tbody></table></div>)}</Card>
    </>
  );
}

export function AdminDepartments() {
  const toast = useToast();
  const { data, loading, error, reload } = useLive(() => api('/api/departments'), [], 30000);
  const [form, setForm] = useState({ name: '', code: '', dailyCapacity: 30, emergencyReserve: 3 }); const [busy, setBusy] = useState(false);
  const [rename, setRename] = useState({});
  const add = async (e) => {
    e.preventDefault(); setBusy(true);
    try { await api('/api/departments', { method: 'POST', body: { ...form, dailyCapacity: Number(form.dailyCapacity), emergencyReserve: Number(form.emergencyReserve) } });
      toast('Department added.', 'ok'); setForm({ name: '', code: '', dailyCapacity: 30, emergencyReserve: 3 }); reload(); }
    catch (x) { toast(x.message, 'danger'); } finally { setBusy(false); }
  };
  const saveName = async (d) => {
    try { await api(`/api/departments/${d.departmentId}`, { method: 'PUT', body: { ...d, name: rename[d.departmentId] } }); toast('Saved.', 'ok'); reload(); }
    catch (x) { toast(x.message, 'danger'); }
  };
  const remove = async (d) => {
    if (!window.confirm(`Delete ${d.name}?`)) return;
    try { await api(`/api/departments/${d.departmentId}`, { method: 'DELETE' }); toast('Department deleted.', 'ok'); reload(); } catch (x) { toast(x.message, 'danger'); }
  };
  return (
    <>
      <h1>Departments</h1><ErrorBox message={error} />
      <form className="card form" onSubmit={add}>
        <h3>Add department</h3>
        <label className="field">Name<input required value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></label>
        <label className="field">Token code (2-4 letters, e.g. GM)<input required maxLength={4} minLength={2} value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value.toUpperCase() })} /></label>
        <label className="field">Daily capacity<input type="number" min="1" value={form.dailyCapacity} onChange={(e) => setForm({ ...form, dailyCapacity: e.target.value })} /></label>
        <label className="field">Emergency reserve<input type="number" min="0" value={form.emergencyReserve} onChange={(e) => setForm({ ...form, emergencyReserve: e.target.value })} /></label>
        <button className="btn primary" disabled={busy}>Add department</button>
      </form>
      <Card>{loading ? <Loading /> : (
        <div className="scroll"><table><thead><tr><th>Name</th><th>Code</th><th>Capacity</th><th /></tr></thead>
          <tbody>{(data || []).map((d) => (
            <tr key={d.departmentId}><td><input value={rename[d.departmentId] ?? d.name} onChange={(e) => setRename({ ...rename, [d.departmentId]: e.target.value })} /></td>
              <td>{d.code}</td><td>{d.dailyCapacity} ({d.emergencyReserve} reserved)</td>
              <td className="row"><button className="btn small primary" disabled={(rename[d.departmentId] ?? d.name) === d.name} onClick={() => saveName(d)}>Save name</button>
                <button className="btn small danger" onClick={() => remove(d)}>Delete</button></td></tr>))}</tbody></table></div>)}</Card>
    </>
  );
}

export function AdminCapacity() {
  const toast = useToast();
  const { data, loading, error, reload } = useLive(async () => {
    const deps = await api('/api/departments');
    const caps = await Promise.all(deps.map((d) => api(`/api/queues/capacity/${d.departmentId}`)));
    return deps.map((d, i) => ({ d, c: caps[i] }));
  }, [], 10000);
  const [edit, setEdit] = useState({});
  if (loading) return <Loading />;
  const save = async (d) => {
    const e = edit[d.departmentId] || {};
    try { await api(`/api/departments/${d.departmentId}`, { method: 'PUT', body: { ...d, ...e } }); toast(`${d.name} saved.`, 'ok'); setEdit({ ...edit, [d.departmentId]: undefined }); reload(); }
    catch (x) { toast(x.message, 'danger'); }
  };
  const bar = (used, total, cls) => <div className="meter"><div className={`fill ${cls}`} style={{ width: `${total ? Math.min(100, (used / total) * 100) : 0}%` }} /></div>;
  return (
    <>
      <h1>Emergency capacity</h1><ErrorBox message={error} />
      <p className="muted">Regular patients can use only the normal capacity (daily capacity minus the emergency reserve). The reserve is kept for emergency registrations.</p>
      {(data || []).map(({ d, c }) => {
        const e = edit[d.departmentId] || {}; const daily = e.dailyCapacity ?? d.dailyCapacity; const res = e.emergencyReserve ?? d.emergencyReserve;
        return (
          <Card key={d.departmentId} title={d.name}>
            <div className="grid2">
              <div><div className="muted small">Normal: {c.normalUsed} / {c.normalCapacity}</div>{bar(c.normalUsed, c.normalCapacity, 'blue')}
                <div className="muted small">Emergency reserve: {c.emergencyUsed} / {c.emergencyReserve}</div>{bar(c.emergencyUsed, c.emergencyReserve, 'red')}</div>
              <div className="row">
                <label className="field">Daily capacity<input type="number" min="1" value={daily} onChange={(x) => setEdit({ ...edit, [d.departmentId]: { ...e, dailyCapacity: Number(x.target.value) } })} /></label>
                <label className="field">Emergency reserve<input type="number" min="0" value={res} onChange={(x) => setEdit({ ...edit, [d.departmentId]: { ...e, emergencyReserve: Number(x.target.value) } })} /></label>
                <button className="btn primary" onClick={() => save(d)}>Save</button>
              </div>
            </div>
            <p className="muted small">Normal capacity would be {daily - res}. Remaining reserve: {c.remainingReserve}.</p>
          </Card>);
      })}
    </>
  );
}

const NUM = [['graceMinutes', 'No-show grace period (min)', 'Time a called patient has to arrive.'], ['maxActivePerDoctor', 'Max active patients per doctor', 'The queue is "full" at this size.'],
  ['defaultConsultationMinutes', 'Default consultation length (min)', 'Used until a doctor has history.'], ['waitBoostPerMinute', 'Fairness boost per waiting minute', 'Priority gained for each minute waited.'],
  ['maxWaitBoost', 'Maximum fairness boost', 'Cap for the waiting-time boost.'], ['emergencyPriority', 'Emergency priority', 'Added for emergency entries.'],
  ['wWait', 'Recommendation weight: predicted wait', ''], ['wQueue', 'Recommendation weight: queue length', ''], ['wAvailability', 'Recommendation weight: availability', ''],
  ['wFairness', 'Recommendation weight: fairness', ''], ['wIdle', 'Recommendation weight: idle doctor', '']];

export function AdminRules() {
  const toast = useToast();
  const [cfg, setCfg] = useState(null); const [err, setErr] = useState('');
  useEffect(() => { api('/api/config/queue-rules').then(setCfg).catch((e) => setErr(e.message)); }, []);
  if (!cfg) return err ? <ErrorBox message={err} /> : <Loading />;
  const toggle = (s) => { const cur = new Set(cfg.cancellableStatuses); cur.has(s) ? cur.delete(s) : cur.add(s); setCfg({ ...cfg, cancellableStatuses: [...cur] }); };
  const save = async (e) => {
    e.preventDefault();
    try { setCfg(await api('/api/config/queue-rules', { method: 'PUT', body: cfg })); toast('Rules saved. They apply within about 15 seconds.', 'ok'); } catch (x) { toast(x.message, 'danger'); }
  };
  return (
    <>
      <h1>Queue rules</h1><ErrorBox message={err} />
      <form className="card form" onSubmit={save}>
        <h3>Policies</h3>
        <label className="check"><input type="checkbox" checked={cfg.lateKeepsPosition} onChange={(e) => setCfg({ ...cfg, lateKeepsPosition: e.target.checked })} /> Late patients keep their position</label>
        <label className="check"><input type="checkbox" checked={cfg.autoCallNext} onChange={(e) => setCfg({ ...cfg, autoCallNext: e.target.checked })} /> Automatically call the next patient after a consultation</label>
        <fieldset><legend>Patient may cancel while status is</legend>
          {['WAITING', 'CHECKED_IN'].map((s) => <label className="check" key={s}><input type="checkbox" checked={cfg.cancellableStatuses.includes(s)} onChange={() => toggle(s)} /> {s.replace('_', ' ')}</label>)}</fieldset>
        <h3>Numbers</h3>
        {NUM.map(([k, label, hint]) => (
          <label className="field" key={k}>{label}{hint && <small> · {hint}</small>}
            <input type="number" step="any" min="0" value={cfg[k]} onChange={(e) => setCfg({ ...cfg, [k]: Number(e.target.value) })} /></label>))}
        <button className="btn primary">Save rules</button>
      </form>
    </>
  );
}

export function AdminStaff() {
  const toast = useToast();
  const docs = useLive(() => api('/api/doctors'), [], 60000);
  const staff = useLive(() => api('/api/admin/staff'), [], 20000);
  const blank = { name: '', email: '', password: '', role: 'RECEPTIONIST', doctorId: '' };
  const [f, setF] = useState(blank); const [busy, setBusy] = useState(false);
  const submit = async (e) => {
    e.preventDefault(); setBusy(true);
    try { await api('/api/admin/staff', { method: 'POST', body: { ...f, doctorId: f.role === 'DOCTOR' ? f.doctorId : null } }); toast('Account created.', 'ok'); setF(blank); staff.reload(); }
    catch (x) { toast(x.message, 'danger'); } finally { setBusy(false); }
  };
  return (
    <>
      <h1>Staff accounts</h1>
      <form className="card form" onSubmit={submit}>
        <h3>Create staff login</h3>
        <label className="field">Name<input required value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} /></label>
        <label className="field">Email<input type="email" required value={f.email} onChange={(e) => setF({ ...f, email: e.target.value })} /></label>
        <label className="field">Password (8+ characters)<input type="password" minLength={8} required value={f.password} onChange={(e) => setF({ ...f, password: e.target.value })} /></label>
        <label className="field">Role<select value={f.role} onChange={(e) => setF({ ...f, role: e.target.value })}><option>RECEPTIONIST</option><option>DOCTOR</option><option>ADMIN</option></select></label>
        {f.role === 'DOCTOR' && <label className="field">Doctor profile<select required value={f.doctorId} onChange={(e) => setF({ ...f, doctorId: e.target.value })}>
          <option value="">Select...</option>{(docs.data || []).map((d) => <option key={d.doctorId} value={d.doctorId}>{d.name}</option>)}</select></label>}
        <button className="btn primary" disabled={busy}>Create account</button>
      </form>
      <Card title="Existing staff logins">{staff.loading ? <Loading /> : (staff.data || []).length === 0 ? <Empty text="None yet." /> : (
        <div className="scroll"><table><thead><tr><th>Email</th><th>Role</th><th>Doctor</th></tr></thead>
          <tbody>{staff.data.map((u) => <tr key={u.uid}><td>{u.email}</td><td>{u.role}</td><td>{u.doctorId || '—'}</td></tr>)}</tbody></table></div>)}</Card>
    </>
  );
}

export function AdminSimulator() {
  const toast = useToast();
  const [dep, setDep] = useState(''); const [cancel, setCancel] = useState(20); const [noShow, setNoShow] = useState(10);
  const [busy, setBusy] = useState(false); const [result, setResult] = useState(null);
  const run = async (label, path, body, okText) => {
    if (!dep && path !== 'reset') return toast('Choose a department first.', 'warn');
    setBusy(true); setResult(null);
    try { const r = await api(`/api/admin/simulate/${path}`, { method: 'POST', body }); setResult({ label, r }); toast(okText(r), 'ok'); }
    catch (x) { toast(x.message, 'danger'); } finally { setBusy(false); }
  };
  const b = (count) => ({ departmentId: dep, count, cancelPercent: cancel, noShowPercent: noShow });
  return (
    <>
      <h1>Demo simulator</h1>
      <div className="alert warn">Development and demo feature. It creates fake patients and fake consultation history. Use "Remove all demo data" before real use.</div>
      <Card>
        <DeptSelect value={dep} onChange={setDep} />
        <label className="field">Share of online demo patients who cancel: {cancel}%<input type="range" min="0" max="100" value={cancel} onChange={(e) => setCancel(Number(e.target.value))} /></label>
        <label className="field">Share of walk-in demo patients who are no-shows: {noShow}%<input type="range" min="0" max="100" value={noShow} onChange={(e) => setNoShow(Number(e.target.value))} /></label>
        <div className="row">
          <button className="btn primary" disabled={busy} onClick={() => run('patients', 'patients', b(10), (r) => `Created ${r.created} demo patients.`)}>Generate 10 patients</button>
          <button className="btn primary" disabled={busy} onClick={() => run('patients', 'patients', b(20), (r) => `Created ${r.created} demo patients.`)}>Generate 20 patients</button>
          <button className="btn ghost" disabled={busy} onClick={() => run('history', 'history', b(50), (r) => `Created ${r.created} consultation records.`)}>Generate 50 consultation records</button>
          <button className="btn danger" disabled={busy} onClick={() => window.confirm('Delete all demo data?') && run('reset', 'reset', {}, (r) => `Removed ${r.removedQueueEntries} entries and ${r.removedConsultations} records.`)}>Remove all demo data</button>
        </div>
        <p className="muted small">Patients are added through the real queue engine, so capacity, tokens and rules apply. A doctor must be ONLINE in the department. Consultation records give the charts data with variable durations and waits.</p>
      </Card>
      {result && <Card title={`Result: ${result.label}`}><pre className="pre">{JSON.stringify(result.r, null, 2)}</pre></Card>}
    </>
  );
}

export function AdminSettings() {
  const { me, user } = useAuth();
  return (
    <>
      <h1>System settings</h1>
      <Card title="This installation">
        <p>Signed in as <b>{user.email}</b> ({me.role})</p><p>API server: {BASE}</p>
        <p>Waiting-time estimates: patients ahead × the doctor's average consultation time. The optional prediction service is not required.</p>
      </Card>
      <Card title="Where to change things"><ul>
        <li>Queue behaviour and fairness: <b>Queue Rules</b></li><li>Daily slots and emergency reserve: <b>Emergency Capacity</b></li>
        <li>Doctors and departments: their own pages</li><li>Logins for staff: <b>Staff Accounts</b></li></ul></Card>
    </>
  );
}
