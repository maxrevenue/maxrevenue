/**
 * Reconstructed Squeeze page — now posts to Worker POST /api/leads
 * Route: /squeeze  (alias: /opt-in -> /squeeze)
 * On success: navigate to /magnet-thank-you; map also at /setup-map
 */
import { Link, useNavigate } from "react-router-dom";

const LEGAL_FOOTER = /* see LEGAL_FOOTER.txt */ undefined;

export function SqueezePage() {
  const navigate = useNavigate();

  async function onSubmit(e) {
    e.preventDefault();
    const form = e.currentTarget;
    const name = form.name.value.trim();
    const email = form.email.value.trim();
    const btn = form.querySelector('button[type="submit"]');
    if (btn) {
      btn.disabled = true;
      btn.textContent = "Sending…";
    }
    try {
      const res = await fetch("/api/leads", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name, email, source: "squeeze" }),
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || "Submit failed");
      navigate("/magnet-thank-you");
    } catch (err) {
      if (btn) {
        btn.disabled = false;
        btn.textContent = "Send me the map";
      }
      alert(err.message || "Something went wrong. Please try again.");
    }
  }

  return (
    <main className="mx-auto min-h-dvh max-w-lg bg-slate-50 px-4 py-12 sm:px-6">
      <h1 className="font-sans text-3xl font-bold text-slate-900">
        Free setup map for beginners
      </h1>
      <p className="mt-4 font-serif text-slate-600">
        A short PDF-style checklist: tools, order of operations, and what to customize first. No full sales video on this page.
      </p>
      <form onSubmit={onSubmit} className="mt-8 space-y-4">
        <div>
          <label htmlFor="squeeze-name" className="block font-sans text-sm font-semibold text-slate-700">
            First name
          </label>
          <input
            id="squeeze-name"
            name="name"
            type="text"
            autoComplete="given-name"
            required
            className="mt-1 w-full rounded-lg border border-slate-300 bg-white px-4 py-3 font-sans text-base text-slate-900 caret-slate-900 placeholder:text-slate-400 outline-none focus:border-slate-500 focus:ring-2 focus:ring-slate-400/30"
          />
        </div>
        <div>
          <label htmlFor="squeeze-email" className="block font-sans text-sm font-semibold text-slate-700">
            Email
          </label>
          <input
            id="squeeze-email"
            name="email"
            type="email"
            autoComplete="email"
            required
            className="mt-1 w-full rounded-lg border border-slate-300 bg-white px-4 py-3 font-sans text-base text-slate-900 caret-slate-900 placeholder:text-slate-400 outline-none focus:border-slate-500 focus:ring-2 focus:ring-slate-400/30"
          />
        </div>
        <p className="font-sans text-xs text-slate-500">
          No phone field on this form. Submitting does not authorize sales calls — only the free guide.
        </p>
        <button type="submit" className="w-full rounded-full bg-slate-900 py-4 font-sans font-bold text-white">
          Send me the map
        </button>
      </form>
      {/* legal footer */}
    </main>
  );
}

export function MagnetThankYouPage() {
  return (
    <main className="mx-auto min-h-dvh max-w-lg bg-slate-50 px-4 py-12 sm:px-6">
      <h1 className="font-sans text-3xl font-bold text-slate-900">Your setup map is on its way</h1>
      <p className="mt-4 font-serif text-slate-600">
        Check your inbox for the free walkthrough checklist. No sales video on this page — just the map you asked for.
      </p>
      <p className="mt-4 font-serif text-sm text-slate-600">
        Over the next few days you'll receive follow-up emails with the full setup walkthrough. When you are ready for the training kit and software access, continue below.
      </p>
      <Link to="/offer" className="mt-8 inline-block rounded-full bg-slate-900 px-8 py-4 font-sans font-bold text-white">
        View the $17 training kit
      </Link>
    </main>
  );
}
