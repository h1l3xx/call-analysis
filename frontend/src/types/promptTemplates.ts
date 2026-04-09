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
  direction?: 'internal_incoming' | 'internal_outgoing' | 'external_incoming' | 'external_outgoing'
}

export interface SuggestResponse {
  suggestions: string[]
}
