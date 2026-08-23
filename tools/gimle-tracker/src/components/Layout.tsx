import { useState, type FormEvent } from "react";
import { NavLink, Outlet, useNavigate } from "react-router-dom";
import { useGimleData } from "../DataContext";

export default function Layout() {
  const data = useGimleData();
  const navigate = useNavigate();
  const [query, setQuery] = useState("");

  function submitSearch(e: FormEvent) {
    e.preventDefault();
    const q = query.trim();
    navigate(q ? `/requirements?q=${encodeURIComponent(q)}` : "/requirements");
  }

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <svg className="brand-mark" viewBox="0 0 32 32" fill="none" aria-hidden="true">
            <rect x="1" y="1" width="30" height="30" rx="7" fill="var(--ink)" />
            <path
              d="M8 20V17L16 10L24 17V20"
              stroke="var(--gold)"
              strokeWidth="2.3"
              strokeLinejoin="round"
              strokeLinecap="round"
            />
            <line x1="11" y1="22" x2="21" y2="22" stroke="var(--gold)" strokeWidth="2.3" strokeLinecap="round" />
          </svg>
          <div className="brand-word">
            GIMLÉ
            <small>Requirements Tracker</small>
          </div>
        </div>

        <form className="search" onSubmit={submitSearch} role="search">
          <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <circle cx="7" cy="7" r="5.2" stroke="currentColor" strokeWidth="1.5" />
            <line x1="10.8" y1="10.8" x2="14" y2="14" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
          </svg>
          <input
            type="text"
            placeholder="Search requirements, modules, files…"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            aria-label="Search requirements"
          />
        </form>

        <nav className="viewtabs">
          <NavLink to="/" end className={({ isActive }) => (isActive ? "active" : "")}>
            Dashboard
          </NavLink>
          <NavLink to="/requirements" className={({ isActive }) => (isActive ? "active" : "")}>
            Requirements
          </NavLink>
        </nav>

        <div className="scanmeta">
          <span className="dot" aria-hidden="true" />
          <span className="mono" title={data.dataDir}>
            {data.summary.total} requirements
          </span>
          {typeof data.rtmMeta.scanDate === "string" && (
            <>
              <span>·</span>
              <span className="mono">scanned {String(data.rtmMeta.scanDate)}</span>
            </>
          )}
        </div>
      </header>

      <main className="content-outer">
        <Outlet />
      </main>
    </div>
  );
}
