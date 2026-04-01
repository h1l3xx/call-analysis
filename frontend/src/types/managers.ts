export interface ManagerResponse {
  id: string
  userId: string
  fullName: string
  email: string
  departmentId: string | null
  departmentName: string | null
  extension: string | null
  phoneNumber: string | null
  isActive: boolean
  createdAt: number
}
