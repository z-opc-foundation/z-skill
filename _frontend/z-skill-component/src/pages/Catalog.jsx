/** 技能目录：/skill/list + 分类过滤 + 搜索 + 已安装视图。 */
import {useEffect, useState} from 'react'
import {Button, Select, Space, Tag, Typography} from 'antd'
import {ReloadOutlined, SearchOutlined} from '@ant-design/icons'
import {ErrorState, ListTable, SearchInput, TableToolbar} from '@yuku123/z-frontend-common/ui'
import {skillApi} from '../services/api'

const {Title, Paragraph, Text} = Typography

function rowsOf(res) {
    const data = res?.skills || res?.list || res?.data || res
    return Array.isArray(data) ? data : []
}

export default function SkillCatalog() {
    const [rows, setRows] = useState([])
    const [categories, setCategories] = useState([])
    const [category, setCategory] = useState(null)
    const [q, setQ] = useState('')
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState(null)

    // kwOverride 让 Enter 用「输入框此刻的关键词」查询，不必等防抖回写 state。
    // typeof 兜一层：antd 的 onPressEnter / onClick 会把事件对象当第一个参数传进来。
    const fetch = async (kwOverride) => {
        const kw = typeof kwOverride === 'string' ? kwOverride : q
        setLoading(true)
        try {
            const [r, cats] = await Promise.all([
                kw ? skillApi.search(kw, category) : skillApi.list(category),
                skillApi.categories().catch(() => null),
            ])
            setRows(rowsOf(r))
            const catArr = cats?.categories || cats
            setCategories(Array.isArray(catArr) ? catArr : [])
            setError(null)
        } catch (e) {
            setError(e?.message || String(e))
        } finally { setLoading(false) }
    }

    useEffect(() => { fetch() }, [category])

    const columns = [
        {title: '技能名', key: 'name', width: 200,
            render: (_, r) => <Text code>{r.name || r.skillName || r.code || '—'}</Text>},
        {title: '分类', key: 'category', width: 140,
            render: (_, r) => r.category ? <Tag color="blue">{r.category}</Tag> : '—'},
        {title: '描述', key: 'desc', ellipsis: true,
            render: (_, r) => r.description || r.desc || '—'},
        {title: '版本', key: 'version', width: 100,
            render: (_, r) => r.version || '—'},
        {title: '已安装', key: 'installed', width: 90,
            render: (_, r) => r.installed ? <Tag color="green">已装</Tag> : <Tag>未装</Tag>},
    ]

    return (
        <div>
            <TableToolbar
                left={
                    <>
                        <Title level={4} style={{margin: 0}}>技能目录</Title>
                        <SearchInput
                            value={q}
                            onChange={setQ}
                            onSearch={(v) => fetch(v)}
                            placeholder="搜索技能"
                            width={220}
                        />
                        <Select value={category} onChange={setCategory} allowClear placeholder="分类"
                                style={{width: 160}}>
                            {categories.map(c => (
                                <Select.Option key={typeof c === 'string' ? c : c.code} value={typeof c === 'string' ? c : c.code}>
                                    {typeof c === 'string' ? c : (c.name || c.code)}
                                </Select.Option>
                            ))}
                        </Select>
                    </>
                }
                right={
                    <>
                        <Button type="primary" icon={<SearchOutlined/>} onClick={() => fetch()} loading={loading}>搜索</Button>
                        <Button icon={<ReloadOutlined/>} onClick={() => fetch()}>刷新</Button>
                    </>
                }
            />
            <Paragraph type="secondary">/skill/list（按分类）/ /skill/search（按关键词）/ /skill/categories 分类字典。</Paragraph>

            {error && (
                <div style={{marginBottom: 16}}>
                    <ErrorState error={error} title="后端未连接" onRetry={() => fetch()}/>
                </div>
            )}

            {/* 分页/空态由基座 ListTable 统一承接。
                搜索与分类过滤走后端（/skill/search、/skill/list），
                工具条里已经有 SearchInput + 分类下拉，所以不传 searchKeys ——
                再加一个过滤框就是页面上两个搜索框。 */}
            <ListTable
                rows={rows}
                columns={columns}
                rowKey={(r, i) => r.name || r.code || i}
                loading={loading}
                emptyTitle="暂无技能"
                emptyDescription="换个关键词或分类再找找"
            />
        </div>
    )
}
