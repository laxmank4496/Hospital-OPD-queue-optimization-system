import { auth } from './firebase';

export const BASE = import.meta.env.VITE_API_URL || 'http://localhost:8080';

/** All business logic lives in the backend; this is only transport + error mapping. */
export async function api(path, { method = 'GET', body } = {}) {
  const headers = { 'Content-Type': 'application/json' };
  const user = auth.currentUser;
  if (user) headers.Authorization = 'Bearer ' + (await user.getIdToken());
  let res;
  try {
    res = await fetch(BASE + path, { method, headers, body: body ? JSON.stringify(body) : undefined });
  } catch {
    throw new Error('Cannot reach the server. Please check your connection.');
  }
  if (res.status === 204) return null;
  const data = await res.json().catch(() => null);
  if (!res.ok) throw new Error(data?.message || 'Something went wrong. Please try again.');
  return data;
}
