export interface PromptTemplateResponse {
  id: string
  name: string
  description: string | null
  content: string
  kind: string
  isSystem: boolean
  updatedAt: number
}

export interface CreatePromptTemplateRequest {
  name: string
  description?: string
  content?: string
}

export interface SuggestResponse {
  suggestions: string[]
}
