const COLORS = { WAITING: 'blue', CHECKED_IN: 'purple', CALLED: 'orange', IN_CONSULTATION: 'yellow',
  COMPLETED: 'green', CANCELLED: 'gray', NO_SHOW: 'red', LATE: 'amber', RESCHEDULED: 'gray' };

export const StatusBadge = ({ status }) => (
  <span className={`badge ${COLORS[status] || 'gray'}`}>{String(status).replace('_', ' ')}</span>
);

export const Loading = ({ text = 'Loading...' }) => <p className="muted" role="status">{text}</p>;
export const ErrorBox = ({ message }) => message ? <div className="alert danger" role="alert">{message}</div> : null;
export const Empty = ({ text }) => <p className="empty">{text}</p>;
export const Card = ({ title, children, className = '' }) => (
  <section className={`card ${className}`}>{title && <h3>{title}</h3>}{children}</section>
);
export const Stat = ({ label, value }) => (
  <div className="stat"><div className="stat-value">{value}</div><div className="stat-label">{label}</div></div>
);
export const mins = (m) => `~${Math.round(m)} min`;
