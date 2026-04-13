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

export interface ManagerEvaluationResponse {
  id: string
  managerId: string
  periodFrom: number | null
  periodTo: number | null
  callCount: number
  avgScore: number | null
  assessment: string | null   // raw JSON: { summary_text, strengths[], weaknesses[], top_recommendations[], performance_level }
  createdAt: number
}

export interface AddPhoneRequest {
  phoneNumber: string
  label?: string
  isPrimary?: boolean
}
