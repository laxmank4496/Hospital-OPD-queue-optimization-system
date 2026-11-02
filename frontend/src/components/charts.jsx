import { Bar, BarChart, CartesianGrid, Cell, Legend, Line, LineChart, Pie, PieChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { Card, Empty } from './ui';

const PALETTE = ['#1d6fd6', '#1e9e5a', '#d98e04', '#d33a3a', '#7a4fd0', '#0f9fb0'];
const has = (d) => Array.isArray(d) && d.length > 0;

export function BarCard({ title, data, x, bars, unit }) {
  return (
    <Card title={title} className="chart">
      {!has(data) ? <Empty text="No data yet." /> : (
        <ResponsiveContainer width="100%" height={240}>
          <BarChart data={data} margin={{ top: 8, right: 8, left: -12, bottom: 0 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="#e1e6ec" /><XAxis dataKey={x} fontSize={12} /><YAxis fontSize={12} unit={unit} allowDecimals />
            <Tooltip />{bars.length > 1 && <Legend />}
            {bars.map((b, i) => <Bar key={b.key} dataKey={b.key} name={b.name || b.key} fill={b.color || PALETTE[i]} radius={[4, 4, 0, 0]} />)}
          </BarChart>
        </ResponsiveContainer>)}
    </Card>
  );
}

export function LineCard({ title, data, x, lines, unit }) {
  return (
    <Card title={title} className="chart">
      {!has(data) ? <Empty text="No data yet." /> : (
        <ResponsiveContainer width="100%" height={240}>
          <LineChart data={data} margin={{ top: 8, right: 8, left: -12, bottom: 0 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="#e1e6ec" /><XAxis dataKey={x} fontSize={12} /><YAxis fontSize={12} unit={unit} />
            <Tooltip />{lines.length > 1 && <Legend />}
            {lines.map((l, i) => <Line key={l.key} type="monotone" dataKey={l.key} name={l.name || l.key} stroke={l.color || PALETTE[i]} strokeWidth={2} dot={{ r: 3 }} />)}
          </LineChart>
        </ResponsiveContainer>)}
    </Card>
  );
}

export function PieCard({ title, data }) {
  const total = (data || []).reduce((a, d) => a + d.value, 0);
  return (
    <Card title={title} className="chart">
      {total === 0 ? <Empty text="No data yet." /> : (
        <ResponsiveContainer width="100%" height={240}>
          <PieChart>
            <Pie data={data} dataKey="value" nameKey="name" innerRadius={50} outerRadius={85} paddingAngle={2}>
              {data.map((d, i) => <Cell key={d.name} fill={['#1e9e5a', '#8a96a3', '#d33a3a'][i] || PALETTE[i]} />)}
            </Pie>
            <Tooltip /><Legend />
          </PieChart>
        </ResponsiveContainer>)}
    </Card>
  );
}
