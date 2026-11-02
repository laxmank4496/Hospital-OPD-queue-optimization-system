import { Navigate, Route, Routes } from 'react-router-dom';
import Shell from './layouts/Shell';
import Landing from './pages/Landing';
import About from './pages/About';
import { Login, Register } from './pages/Auth';
import QueueDisplay from './pages/QueueDisplay';
import { PatientDashboard, MyQueue, FindDoctor, PatientHistory, Profile } from './pages/Patient';
import { DoctorDashboard, DoctorQueue, DoctorConsultation, DoctorAvailability, DoctorStats } from './pages/Doctor';
import { ReceptionDashboard, RegisterWalkin, LiveQueue, CheckIn } from './pages/Reception';
import { AdminDashboard, AdminAnalytics, AdminDoctors, AdminDepartments, AdminCapacity, AdminRules, AdminStaff, AdminSimulator, AdminSettings } from './pages/Admin';

const redirect = (to) => <Navigate to={to} replace />;

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Landing />} />
      <Route path="/about" element={<About />} />
      <Route path="/login" element={<Login />} />
      <Route path="/register" element={<Register />} />
      <Route path="/queue-display/:departmentId" element={<QueueDisplay />} />

      <Route path="/patient" element={<Shell role="PATIENT" />}>
        <Route path="dashboard" element={<PatientDashboard />} />
        <Route path="my-queue" element={<MyQueue />} />
        <Route path="find-doctor" element={<FindDoctor />} />
        <Route path="join-queue" element={redirect('/patient/find-doctor')} />
        <Route path="history" element={<PatientHistory />} />
        <Route path="profile" element={<Profile />} />
      </Route>

      <Route path="/doctor" element={<Shell role="DOCTOR" />}>
        <Route path="dashboard" element={<DoctorDashboard />} />
        <Route path="queue" element={<DoctorQueue />} />
        <Route path="consultation" element={<DoctorConsultation />} />
        <Route path="availability" element={<DoctorAvailability />} />
        <Route path="statistics" element={<DoctorStats />} />
      </Route>

      <Route path="/receptionist" element={<Shell role="RECEPTIONIST" />}>
        <Route path="dashboard" element={<ReceptionDashboard />} />
        <Route path="register-walkin" element={<RegisterWalkin />} />
        <Route path="queue" element={<LiveQueue />} />
        <Route path="checkin" element={<CheckIn />} />
      </Route>

      <Route path="/admin" element={<Shell role="ADMIN" />}>
        <Route path="dashboard" element={<AdminDashboard />} />
        <Route path="doctors" element={<AdminDoctors />} />
        <Route path="departments" element={<AdminDepartments />} />
        <Route path="analytics" element={<AdminAnalytics />} />
        <Route path="emergency-capacity" element={<AdminCapacity />} />
        <Route path="staff" element={<AdminStaff />} />
        <Route path="simulator" element={<AdminSimulator />} />
        <Route path="settings" element={<AdminSettings />} />
        <Route path="queue-rules" element={<AdminRules />} />
      </Route>
      <Route path="*" element={redirect('/')} />
    </Routes>
  );
}
