import { useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { ErrorBox } from '../components/ui';

const friendly = (e) => /invalid-credential|wrong-password|user-not-found/.test(e.code || e.message) ? 'Incorrect email or password.'
  : /email-already-in-use/.test(e.code || '') ? 'An account with this email already exists.'
  : /weak-password/.test(e.code || '') ? 'Password must be at least 6 characters.' : ('Could not complete the request (' + (e.code || e.message) + ').');

export function Login() {
  const { login } = useAuth(); const nav = useNavigate(); const [q] = useSearchParams();
  const [f, setF] = useState({ email: '', pw: '' }); const [err, setErr] = useState(''); const [busy, setBusy] = useState(false);
  const submit = async (e) => {
    e.preventDefault(); setBusy(true); setErr('');
    try { await login(f.email, f.pw); nav('/patient/dashboard'); } // Shell redirects to the correct role home
    catch (x) { setErr(friendly(x)); } finally { setBusy(false); }
  };
  return (
    <form className="auth card" onSubmit={submit}>
      <h2>{q.get('staff') ? 'Staff login' : 'Patient login'}</h2>
      <ErrorBox message={err} />
      <label>Email<input type="email" required value={f.email} onChange={(e) => setF({ ...f, email: e.target.value })} /></label>
      <label>Password<input type="password" required value={f.pw} onChange={(e) => setF({ ...f, pw: e.target.value })} /></label>
      <button className="btn primary" disabled={busy}>{busy ? 'Signing in...' : 'Login'}</button>
      <p className="muted">New patient? <Link to="/register">Create an account</Link></p>
    </form>
  );
}

export function Register() {
  const { register } = useAuth(); const nav = useNavigate();
  const [f, setF] = useState({ name: '', email: '', pw: '' }); const [err, setErr] = useState(''); const [busy, setBusy] = useState(false);
  const submit = async (e) => {
    e.preventDefault(); setBusy(true); setErr('');
    try { await register(f.name, f.email, f.pw); nav('/patient/dashboard'); }
    catch (x) { setErr(friendly(x)); } finally { setBusy(false); }
  };
  return (
    <form className="auth card" onSubmit={submit}>
      <h2>Create patient account</h2>
      <ErrorBox message={err} />
      <label>Full name<input required value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} /></label>
      <label>Email<input type="email" required value={f.email} onChange={(e) => setF({ ...f, email: e.target.value })} /></label>
      <label>Password<input type="password" minLength={6} required value={f.pw} onChange={(e) => setF({ ...f, pw: e.target.value })} /></label>
      <button className="btn primary" disabled={busy}>{busy ? 'Creating...' : 'Register'}</button>
      <p className="muted">Staff accounts are created by an administrator.</p>
    </form>
  );
}



