import { useCallback } from "react";
import { useNavigate } from "react-router-dom";

import { ProviderLayout } from "../../../shared/layouts/provider";
import { useAuth } from "../../auth/hooks/useAuth";

import ProviderEarningsPage from "../pages/ProviderEarningsPage";

/**
 * Connects the shared provider layout to the authenticated
 * account and the central authentication logout lifecycle.
 */
export default function ProviderEarningsShell() {
  const navigate = useNavigate();
  const { user, logout } = useAuth();

  const handleLogout = useCallback(async () => {
    await logout();
    navigate("/login", { replace: true });
  }, [logout, navigate]);

  return (
    <ProviderLayout
      user={{
        name: user?.name || "Provider",
        email: user?.email || "",
      }}
      onLogout={handleLogout}
    >
      <ProviderEarningsPage />
    </ProviderLayout>
  );
}
