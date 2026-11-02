import { Link } from 'react-router-dom';
import PublicLayout from '../components/PublicLayout';

const FEATURES = [
  ['Smart doctor recommendation', 'We compare estimated waiting times across the available doctors and highlight the shortest. The choice is always yours.'],
  ['Real-time queue', 'Your position, patients ahead and status update live when the doctor finishes a consultation. No refreshing.'],
  ['Clear waiting-time estimates', 'Estimates come from the number of patients ahead and each doctor\'s real average consultation time. They are never presented as guarantees.'],
  ['Fair queue management', 'Everyone is served in order. Waiting time raises priority, so nobody is skipped indefinitely.'],
  ['Emergency capacity', 'A configurable part of each day\'s slots is reserved, so regular bookings never use up emergency capacity.'],
  ['Online and walk-in together', 'Online patients and walk-ins join one queue with one set of rules, so the order is fair for everyone.'],
];
const ROLES = [
  ['Patients', 'Compare doctors, join the queue, check in and follow your position live.'],
  ['Receptionists', 'Register walk-ins, check patients in by token and watch the live queue.'],
  ['Doctors', 'Set availability, call the next patient, run consultations and see statistics.'],
  ['Administrators', 'Manage doctors and departments, set queue rules and emergency capacity, and monitor performance.'],
];
const FAQ = [
  ['Is the doctor chosen for me?', 'No. We only suggest the doctor with the shortest estimated wait. You decide.'],
  ['Can I cancel?', 'Yes, while your status is Waiting. After you are called, please speak to the desk.'],
  ['What if I arrive late?', 'If you arrive within the grace period after being called, the desk can mark you late and you may keep your place. After that you are marked no-show.'],
  ['How accurate is the waiting time?', 'It is an estimate that changes as consultations run long or short. Treat it as a guide.'],
];

export default function Landing() {
  return (
    <PublicLayout>
      <header className="hero">
        <h1>Smart OPD Queue Management</h1>
        <p>Reduce waiting time. Improve doctor utilization. Keep patients informed in real time.</p>
        <div className="row center"><Link className="btn primary lg" to="/login">Patient Login</Link><Link className="btn bg-[#1d70e2] text-white rounded-lg px-6 py-3 font-medium lg" to="/login?staff=1">Staff Login</Link></div>
        <p className="small">New here? <Link className="lightlink" to="/register">Create a patient account</Link></p>
      </header>
      <section className="section"><h2>How it works</h2>
        <ol className="flow">{['Choose a department', 'Compare doctors and estimated waits', 'Pick a doctor and join the queue', 'Check in and track your position', 'Get called and see the doctor'].map((s, i) => <li key={s}><span>{i + 1}</span>{s}</li>)}</ol></section>
      <section className="section"><h2>What you get</h2><div className="grid3">{FEATURES.map(([t, d]) => <article className="card" key={t}><h3>{t}</h3><p>{d}</p></article>)}</div></section>
      <section className="section"><h2>Built for everyone in the OPD</h2><div className="grid4">{ROLES.map(([t, d]) => <article className="card" key={t}><h3>{t}</h3><p>{d}</p></article>)}</div></section>
      <section className="section narrow"><h2>Questions</h2>{FAQ.map(([q, a]) => <details className="faq" key={q}><summary>{q}</summary><p>{a}</p></details>)}</section>
    </PublicLayout>
  );
}

