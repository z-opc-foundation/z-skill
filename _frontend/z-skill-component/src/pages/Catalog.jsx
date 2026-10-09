/** 技能目录：/skill/list + 分类过滤 + 搜索 + 已安装视图。 */
import {useEffect, useState} from 'react'
import {Alert, Button, Card, Input, Select, Space, Table, Tag, Typography} from 'antd'
import {ReloadOutlined, SearchOutlined} from '@ant-design/icons'
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

    const fetch = async () => {
        setLoading(true)
        try {
            const [r, cats] = await Promise.all([
                q ? skillApi.search(q, category) : skillApi.list(category),
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
            <Space style={{marginBottom: 16}} wrap>
                <Title level={4} style={{margin: 0}}>技能目录</Title>
                <Input value={q} onChange={e => setQ(e.target.value)} onPressEnter={fetch}
                       placeholder="搜索技能" style={{width: 220}} prefix={<SearchOutlined/>}/>
                <Select value={category} onChange={setCategory} allowClear placeholder="分类"
                        style={{width: 160}}>
                    {categories.map(c => (
                        <Select.Option key={typeof c === 'string' ? c : c.code} value={typeof c === 'string' ? c : c.code}>
                            {typeof c === 'string' ? c : (c.name || c.code)}
                        </Select.Option>
                    ))}
                </Select>
                <Button type="primary" icon={<SearchOutlined/>} onClick={fetch} loading={loading}>搜索</Button>
                <Button icon={<ReloadOutlined/>} onClick={fetch}>刷新</Button>
            </Space>
            <Paragraph type="secondary">/skill/list（按分类）/ /skill/search（按关键词）/ /skill/categories 分类字典。</Paragraph>

            {error && <Alert type="error" showIcon style={{marginBottom: 16}} message="后端未连接" description={error}/>}

            <Card>
                <Table rowKey={(r, i) => r.name || r.code || i} dataSource={rows} columns={columns}
                       loading={loading} size="small" pagination={{pageSize: 20}}/>
            </Card>
        </div>
    )
}
