export interface ManagerPhoneResponse {
  id: string
  phoneNumber: string
  label: string | null
  isPrimary: boolean
}

export interface ManagerResponse {
  id: string
  userId: string
  fullName: string
  email: string
  departmentId: string | null
  departmentName: string | null
  extension: string | null
  phoneNumber: string | null
  phoneNumbers: ManagerPhoneResponse[]
  isActive: boolean
  createdAt: number
}

export interface AddPhoneRequest {
  phoneNumber: string
  label?: string
  isPrimary?: boolean
}
