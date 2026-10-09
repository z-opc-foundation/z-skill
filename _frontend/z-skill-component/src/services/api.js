/**
 * z-skill API client：走 /skill/** 面（技能目录：search/list/installed/categories/sources）。
 */
import {createRequest} from '@yuku123/z-frontend-common'

const request = createRequest({baseURL: '', tokenKey: 'zskill_token'})

export default request

export function configureSkill(config) {
    if (config && config.apiBase !== undefined) {
        request.defaults.baseURL = config.apiBase
    }
}

export const skillApi = {
    search: (q, category) => request.get('/skill/search', {params: {q, category}}),
    list: (category) => request.get('/skill/list', {params: category ? {category} : {}}),
    installed: () => request.get('/skill/installed'),
    categories: () => request.get('/skill/categories'),
    sources: () => request.get('/skill/sources'),
}
