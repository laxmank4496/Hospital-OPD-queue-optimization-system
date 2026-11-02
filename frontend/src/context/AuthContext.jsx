import { createContext, useContext, useEffect, useState } from 'react';
import { onAuthStateChanged, signInWithEmailAndPassword, createUserWithEmailAndPassword, updateProfile, signOut } from 'firebase/auth';
import { auth } from '../services/firebase';
import { api } from '../services/api';

const Ctx = createContext(null);
export const useAuth = () => useContext(Ctx);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [me, setMe] = useState(null); // { uid, role, doctorId } — from the VERIFIED token on the server
  const [loading, setLoading] = useState(true);

  useEffect(() => onAuthStateChanged(auth, async (u) => {
    setUser(u);
    if (u) {
      try { await u.getIdToken(true); setMe(await api('/api/users/me')); } catch { setMe(null); }
    } else setMe(null);
    setLoading(false);
  }), []);

  const login = (email, pw) => signInWithEmailAndPassword(auth, email, pw);
  const register = async (name, email, pw) => {
    const c = await createUserWithEmailAndPassword(auth, email, pw);
    await updateProfile(c.user, { displayName: name });
  };
  const logout = () => signOut(auth);
  return <Ctx.Provider value={{ user, me, loading, login, register, logout }}>{children}</Ctx.Provider>;
}
