import { Link } from 'react-router-dom';

export default function PublicLayout({ children }) {
  return (
    <div className="landing">
      <nav className="topbar"><Link to="/" className="brand-dark">＋ Smart OPD</Link>
        <div className="row"><Link to="/about">About</Link><Link className="btn ghost small" to="/login?staff=1">Staff login</Link><Link className="btn primary small" to="/login">Patient login</Link></div></nav>
      {children}
      <footer className="footer"><div>Smart OPD Queue Management · Academic project</div>
        <div className="muted small">Waiting-time estimates are not guarantees. In an emergency, go directly to the hospital emergency department.</div></footer>
    </div>
  );
}
