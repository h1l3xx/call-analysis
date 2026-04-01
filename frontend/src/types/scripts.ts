export interface CreateCriterionRequest {
  orderNum: number
  name: string
  description: string
  groupType?: string
  weight?: number
  scoringType?: string
}

export interface CreateScriptRequest {
  name: string
  callType: string
  description?: string
  criteria?: CreateCriterionRequest[]
}

export interface UpdateScriptRequest {
  name?: string
  callType?: string
  description?: string
  isActive?: boolean
  criteria?: CreateCriterionRequest[]
}

export interface ScriptResponse {
  id: string
  name: string
  callType: string
  description: string | null
  isActive: boolean
  criteriaCount: number
  createdAt: number
  updatedAt: number
}

export interface CriterionResponse {
  id: number
  orderNum: number
  name: string
  description: string
  groupType: string
  weight: number
  scoringType: string
  isActive: boolean
}

export interface ScriptDetailResponse {
  id: string
  name: string
  callType: string
  description: string | null
  isActive: boolean
  criteria: CriterionResponse[]
  createdAt: number
  updatedAt: number
}
