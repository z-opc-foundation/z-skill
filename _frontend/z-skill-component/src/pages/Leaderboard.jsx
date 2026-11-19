/** 排行榜：对齐 skills.sh 首页形态 —— 全部技能按安装量排名的榜单。 */
import {useEffect, useState} from 'react'
import {Button, Tag, Typography} from 'antd'
import {ReloadOutlined} from '@ant-design/icons'
import {ErrorState, ListTable, TableToolbar} from '@yuku123/z-frontend-common/ui'
import {skillApi} from '../services/api'

const {Title, Paragraph, Text} = Typography

function rowsOf(res) {
    const data = res?.skills || res?.list || res?.data || res
    return Array.isArray(data) ? data : []
}

function installsOf(r) {
    return r.install_count ?? r.installs ?? r.installCount ?? 0
}

const RANK_COLORS = {1: '#faad14', 2: '#bfbfbf', 3: '#d48806'}

export default function Leaderboard() {
    const [rows, setRows] = useState([])
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState(null)

    const fetch = async () => {
        setLoading(true)
        try {
            const r = await skillApi.list()
            const sorted = rowsOf(r).slice()
                .sort((a, b) => installsOf(b) - installsOf(a))
            setRows(sorted)
            setError(null)
        } catch (e) {
            setError(e?.message || String(e))
        } finally { setLoading(false) }
    }

    useEffect(() => { fetch() }, [])

    const columns = [
        {title: '#', key: 'rank', width: 64,
            render: (_, __, i) => (
                <Text strong style={{color: RANK_COLORS[i + 1], fontSize: 16}}>{i + 1}</Text>
            )},
        {title: '技能', key: 'name', width: 220,
            render: (_, r) => <Text code strong>{r.name || r.code || '—'}</Text>},
        {title: '描述', key: 'desc', ellipsis: true,
            render: (_, r) => r.description || r.desc || '—'},
        {title: '分类', key: 'category', width: 110,
            render: (_, r) => r.category ? <Tag color="blue">{r.category}</Tag> : '—'},
        {title: '来源', key: 'source', width: 150,
            render: (_, r) => <Text type="secondary">{r.source || r.sourceId || '—'}</Text>},
        {title: '安装量', key: 'installs', width: 110,
            render: (_, r) => <Text strong>{installsOf(r).toLocaleString()}</Text>},
        {title: '状态', key: 'installed', width: 90,
            render: (_, r) => r.installed ? <Tag color="green">已装</Tag> : <Tag>未装</Tag>},
    ]

    return (
        <div>
            <TableToolbar
                left={<Title level={4} style={{margin: 0}}>排行榜</Title>}
                right={<Button icon={<ReloadOutlined/>} onClick={fetch} loading={loading}>刷新</Button>}
            />
            <Paragraph type="secondary">按安装量排名（/skill/list 聚合后排序）。发布越多人装，排名越靠前。</Paragraph>

            {error && (
                <div style={{marginBottom: 16}}>
                    <ErrorState error={error} title="后端未连接" onRetry={fetch}/>
                </div>
            )}

            <ListTable
                rows={rows}
                columns={columns}
                rowKey={(r, i) => r.id || r.name || i}
                loading={loading}
                emptyTitle="榜上还没有技能"
                emptyDescription="去技能目录看看，或先发布一个技能"
            />
        </div>
    )
}
