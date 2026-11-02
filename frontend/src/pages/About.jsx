import { Link } from 'react-router-dom';
import PublicLayout from '../components/PublicLayout';

export default function About() {
  return (
    <PublicLayout>
      <div className="section narrow">
        <h1>About this system</h1>
        <p>Smart OPD Queue Management helps hospitals run an outpatient department with less waiting and more transparency. Patients and walk-ins join the same queue, and everyone can see where they stand.</p>
        <h2>How the queue works</h2>
        <ul>
          <li><b>One queue.</b> Online patients and walk-ins use the same rules. The only difference is how they register.</li>
          <li><b>Fair order.</b> Normally first come, first served. Staff may set a hospital priority, and waiting time adds to priority so no one is skipped for ever.</li>
          <li><b>Live position.</b> Your position is calculated from the live queue every time, so it is always current.</li>
          <li><b>Estimated wait.</b> Patients ahead × the doctor's average consultation time. It is an estimate, not a promise.</li>
          <li><b>Emergency reserve.</b> A part of each day's capacity is kept for emergencies.</li>
          <li><b>No double tokens.</b> The server issues tokens inside a database transaction, so two people never get the same number.</li>
        </ul>
        <h2>Doctor recommendation</h2>
        <p>When several doctors are available we show their estimated waits and mark the one with the shortest, considering queue length, whether the doctor is idle and how long people have already waited. It is a suggestion. You choose.</p>
        <h2>Technology</h2>
        <p>React web app, Java Spring Boot API, Firebase Authentication and Firestore. The same API can serve a future Android app.</p>
        <Link className="btn primary" to="/login">Get started</Link>
      </div>
    </PublicLayout>
  );
}
