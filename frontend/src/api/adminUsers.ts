import request from './request';

export type UserStatus = 'ACTIVE' | 'DISABLED' | 'LOCKED';

export interface AdminUser {
  id: number;
  username: string;
  nickname?: string;
  email?: string;
  mobile?: string;
  status: UserStatus;
  unlimitedInterviews: boolean;
  roles: string[];
  createdAt: string;
}

export interface AdminUserPage {
  items: AdminUser[];
  totalElements: number;
  totalPages: number;
  page: number;
  size: number;
}

export const adminUsersApi = {
  list: (page = 0, size = 20) =>
    request.get<AdminUserPage>('/api/admin/users', { params: { page, size } }),
  updateStatus: (userId: number, status: UserStatus) =>
    request.patch<AdminUser>(`/api/admin/users/${userId}/status`, { status }),
  updateUnlimitedInterviews: (userId: number, enabled: boolean) =>
    request.patch<AdminUser>(`/api/admin/users/${userId}/unlimited-interviews`, { enabled }),
};
