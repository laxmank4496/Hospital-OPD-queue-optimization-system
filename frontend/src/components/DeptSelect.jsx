import { api } from '../services/api';
import { useLive } from '../hooks/useLive';

export default function DeptSelect({ value, onChange }) {
  const { data } = useLive(() => api('/api/departments'), [], 60000);
  return (
    <label className="field">Department
      <select value={value} onChange={(e) => onChange(e.target.value)}>
        <option value="">Select a department...</option>
        {(data || []).map((d) => <option key={d.departmentId} value={d.departmentId}>{d.name}</option>)}
      </select>
    </label>
  );
}
