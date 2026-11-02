import { NavLink, Outlet, Navigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { Loading } from '../components/ui';

const NAV = {
  PATIENT: [['dashboard', 'Dashboard'], ['find-doctor', 'Find Doctor'], ['my-queue', 'My Queue'], ['history', 'Queue History'], ['profile', 'Profile']],
  DOCTOR: [['dashboard', 'Dashboard'], ['queue', 'Current Queue'], ['consultation', 'Consultation'], ['availability', 'Availability'], ['statistics', 'Statistics']],
  RECEPTIONIST: [['dashboard', 'Dashboard'], ['register-walkin', 'Register Walk-in'], ['queue', 'Live Queue'], ['checkin', 'Check-in']],
  ADMIN: [['dashboard', 'Dashboard'], ['analytics', 'Analytics'], ['doctors', 'Doctors'], ['departments', 'Departments'], ['emergency-capacity', 'Emergency Capacity'],
    ['queue-rules', 'Queue Rules'], ['staff', 'Staff Accounts'], ['simulator', 'Demo Simulator'], ['settings', 'System Settings']],
};

/** role: required role. The server enforces it too; this only decides what to render. */
export default function Shell({ role }) {
  const { me, user, loading, logout } = useAuth();
  if (loading) return <Loading />;
  if (!user || !me) return <Navigate to="/login" replace />;
  if (me.role !== role) return <Navigate to={`/${me.role.toLowerCase()}/dashboard`} replace />;
  const base = `/${role.toLowerCase()}`;
  return (
    <div className="shell">
      <aside className="side">
        <div className="brand">＋ Smart OPD</div>
        <nav aria-label="Main">
          {NAV[role].map(([p, label]) => <NavLink key={p} to={`${base}/${p}`}>{label}</NavLink>)}
        </nav>
        <div className="who">{user.displayName || user.email}<br /><small>{role}</small></div>
        <button className="btn ghost" onClick={logout}>Logout</button>
      </aside>
      <main className="main"><Outlet /></main>
    </div>
  );
}
