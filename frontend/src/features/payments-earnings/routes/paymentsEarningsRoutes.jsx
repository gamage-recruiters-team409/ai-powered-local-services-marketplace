import ProviderItemizedEarningsPage from "../pages/ProviderItemizedEarningsPage";
import RequireRole from "../../auth/guards/RequireRole";
import ProviderEarningsShell from "../components/ProviderEarningsShell";

/**
 * WP07 provider routes.
 *
 * Authentication and provider role checks are enforced before
 * rendering financial pages. Backend authorization remains
 * authoritative for all earnings and payout operations.
 */
export const paymentsEarningsRoutes = [
  {
    path: "/provider/earnings",
    element: (
      <RequireRole roles={["PROVIDER"]}>
        <ProviderEarningsShell />
      </RequireRole>
    ),
  },
  {
    path: "/provider/earnings/itemized",
    element: (
      <RequireRole roles={["PROVIDER"]}>
        <ProviderEarningsShell>
          <ProviderItemizedEarningsPage />
        </ProviderEarningsShell>
      </RequireRole>
    ),
  },
];
