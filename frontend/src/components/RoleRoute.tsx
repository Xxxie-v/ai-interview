import { Navigate, Outlet } from 'react-router-dom';
import { getStoredUser } from '../utils/authStorage';

interface RoleRouteProps {
  allowedRoles: string[];
}

export default function RoleRoute({ allowedRoles }: RoleRouteProps) {
  const user = getStoredUser();
  const allowed = user?.roles.some(role => allowedRoles.includes(role));

  if (!allowed) {
    return <Navigate to="/history" replace />;
  }

  return <Outlet />;
}
