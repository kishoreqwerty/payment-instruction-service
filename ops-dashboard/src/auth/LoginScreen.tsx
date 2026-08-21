import { useState, type FormEvent } from "react";
import { useAuth } from "./AuthContext";

export function LoginScreen() {
  const { login, loginError, loggingIn } = useAuth();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    void login(username, password);
  }

  return (
    <div className="login-screen">
      <form className="login-form" onSubmit={handleSubmit}>
        <h1>Payment Exceptions</h1>
        <p className="login-subtitle">Sign in with your operations account.</p>
        <label htmlFor="username">Username</label>
        <input id="username" name="username" autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} autoFocus />
        <label htmlFor="password">Password</label>
        <input
          id="password"
          name="password"
          type="password"
          autoComplete="current-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        {loginError && (
          <p role="alert" className="login-error">
            {loginError}
          </p>
        )}
        <button type="submit" disabled={loggingIn || !username || !password}>
          {loggingIn ? "Signing in…" : "Sign in"}
        </button>
      </form>
    </div>
  );
}
