import { useState } from 'react'
import { useOutletContext } from 'react-router'
import { LuArrowRight, LuBookOpen, LuCirclePlus, LuClipboardCheck, LuClock3, LuListChecks, LuPlay, LuTarget, LuTimer, LuTrendingUp } from 'react-icons/lu'
import { useAuth } from '../../auth/useAuth.js'
import HomeStudyChart from './HomeStudyChart.jsx'
import { filterHomeItems, homeData } from './homeData.js'

function CardImage({ src, alt, className = '' }) {
  const [failed, setFailed] = useState(false)
  return failed ? <div className={'grid h-full w-full place-items-center bg-surface-soft text-primary-strong ' + className} role="img" aria-label={alt}><LuBookOpen size={40} strokeWidth={1.5} aria-hidden="true" /></div> : <img className={'h-full w-full object-cover ' + className} src={src} alt={alt} width={512} height={288} loading="lazy" onError={() => setFailed(true)} />
}

function SectionHeading({ id, title, description }) {
  return <div className="mb-4 flex flex-wrap items-start justify-between gap-x-4 gap-y-1"><div><h2 className="text-2xl leading-tight font-semibold tracking-tight sm:text-[30px]" id={id}>{title}</h2><p className="mt-1 text-sm leading-relaxed text-muted">{description}</p></div><button className="inline-flex min-h-11 shrink-0 cursor-not-allowed items-center gap-1 text-xs font-semibold text-primary-strong" type="button" disabled title="Chưa khả dụng">Xem tất cả<LuArrowRight size={15} aria-hidden="true" /></button></div>
}

function EmptySearch({ searching }) {
  return <p className="rounded-xl bg-surface px-6 py-10 text-center text-sm text-muted">{searching ? 'Không tìm thấy nội dung phù hợp.' : 'Chưa có nội dung.'}</p>
}

const summaryIcons = { words: LuBookOpen, tests: LuClipboardCheck, accuracy: LuTarget, hours: LuClock3 }
const detailIcons = { trend: LuTrendingUp, plus: LuCirclePlus }

function LearningOverview({ summary, week }) {
  if (!summary) return <section id="learning-overview" className="scroll-mt-36 lg:scroll-mt-24" aria-labelledby="overview-title"><h2 className="text-2xl font-semibold sm:text-[30px]" id="overview-title">Tổng quan học tập</h2><p className="mt-4 rounded-xl bg-surface px-6 py-10 text-center text-sm text-muted">Chưa có dữ liệu học tập.</p></section>
  return <section className="scroll-mt-24 outline-none" id="learning-overview" aria-labelledby="overview-title" tabIndex={-1}>
    <div className="mb-4"><h2 className="text-2xl leading-tight font-semibold tracking-tight sm:text-[30px]" id="overview-title">Tổng quan học tập</h2><p className="mt-1 text-sm text-muted">Theo dõi hiệu suất và thời gian rèn luyện</p></div>
    <div className="grid gap-6 xl:grid-cols-2">
      <div className="grid gap-4 sm:grid-cols-2">
        {(summary.cards || []).map(item => {
          const Icon = summaryIcons[item.id] || LuBookOpen
          const DetailIcon = detailIcons[item.detailIcon]
          return <div className="cursor-pointer transition-[transform,box-shadow] duration-200 ease-out hover:shadow-study motion-safe:hover:scale-[1.015] motion-reduce:transition-none flex min-h-40 flex-col justify-between rounded-xl bg-surface p-6 shadow-stat" key={item.id}>
            <div className="flex items-center justify-between gap-3"><p className="text-sm font-medium text-muted">{item.title}</p><span className="grid size-10 shrink-0 place-items-center rounded-lg bg-surface-soft text-primary-strong"><Icon size={22} aria-hidden="true" /></span></div>
            <div className="mt-4">
              <p className="flex flex-wrap items-baseline gap-2"><span className="text-4xl font-bold tracking-tight">{typeof item.value === 'number' ? item.value.toLocaleString('vi-VN') : item.value}{item.suffix === '%' && '%'}</span>{item.suffix && item.suffix !== '%' && <span className="text-lg text-muted">{item.suffix}</span>}{item.detailStyle === 'inline' && <span className="text-[11px] font-medium text-primary">{item.detail}</span>}</p>
              {item.detailStyle === 'badge' && <p className="mt-2 inline-flex items-center gap-1 rounded-full bg-surface-soft px-2 py-0.5 text-[11px] font-semibold text-primary">{DetailIcon && <DetailIcon size={13} aria-hidden="true" />}{item.detail}</p>}
              {item.detailStyle === 'plain' && <p className="mt-2 text-xs text-muted">{item.detail}</p>}
              {typeof item.progress === 'number' && <div className="mt-2 h-2 overflow-hidden rounded-full bg-surface-muted" role="progressbar" aria-label={item.title} aria-valuenow={item.progress} aria-valuemin={0} aria-valuemax={100}><div className="h-full rounded-full bg-linear-to-r from-primary to-primary-strong" style={{ width: item.progress + '%' }} /></div>}
            </div>
          </div>
        })}
      </div>
      <HomeStudyChart week={week} />
    </div>
  </section>
}

export default function HomePage({ data = homeData }) {
  const { summary, week } = data
  const { user } = useAuth()
  const { searchQuery = '' } = useOutletContext() || {}
  const name = user?.username.trim() || 'bạn'
  const vocabulary = filterHomeItems(data.vocabulary, searchQuery)
  const tests = filterHomeItems(data.tests, searchQuery)
  const posts = filterHomeItems(data.posts, searchQuery)

  return <div className="space-y-10">
    <section className="relative overflow-hidden rounded-xl bg-linear-to-r from-surface-soft to-surface-muted/75 px-6 py-8 shadow-stat md:p-10" aria-labelledby="home-title"><div className="relative flex flex-col items-start justify-between gap-6 xl:flex-row xl:items-center"><div className="min-w-0 max-w-2xl"><h1 className="text-3xl leading-tight font-bold tracking-tight [overflow-wrap:anywhere] sm:text-4xl" id="home-title">Xin chào, {name} !</h1><p className="mt-2 max-w-xl text-base leading-relaxed text-muted">Sẵn sàng tiếp tục hành trình học tiếng Anh hôm nay? {summary && <>Bạn đang duy trì chuỗi học {summary.streakDays} ngày liên tiếp!</>}</p></div><button className="inline-flex min-h-11 shrink-0 cursor-not-allowed items-center justify-center gap-2 whitespace-nowrap rounded-lg bg-primary-strong px-6 py-3 text-sm font-semibold text-on-primary shadow-button" type="button" disabled title="Chưa khả dụng">Tiếp tục học<LuArrowRight size={18} aria-hidden="true" /></button></div></section>
    <LearningOverview summary={summary} week={week} />
    {searchQuery.trim() && <p className="text-sm text-muted" role="status">Tìm thấy {vocabulary.length + tests.length + posts.length} nội dung phù hợp.</p>}

    <section className="scroll-mt-24 outline-none" id="continue-learning" aria-labelledby="continue-title" tabIndex={-1}>
      <SectionHeading id="continue-title" title="Tiếp tục học" description="Tiếp tục củng cố vốn từ theo tiến trình cá nhân" />
      {vocabulary.length ? <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">{vocabulary.map(item => <article className="cursor-pointer transition-[transform,box-shadow] duration-200 ease-out hover:shadow-study motion-safe:hover:scale-[1.015] motion-reduce:transition-none min-w-0 overflow-hidden rounded-xl bg-surface shadow-stat" key={item.id}><div className="h-28 overflow-hidden"><CardImage src={item.image} alt={item.title} /></div><div className="p-4"><h3 className="text-lg leading-snug font-semibold">{item.title}</h3><p className="mt-1 text-xs text-muted">{item.learned}/{item.words} từ đã học</p><div className="mt-4"><p className="mb-1.5 flex items-center justify-between text-[11px] text-muted"><span>Tiến độ</span><strong className="text-primary-strong">{item.progress}%</strong></p><div className="h-2 overflow-hidden rounded-full bg-surface-muted" role="progressbar" aria-label={'Tiến độ mẫu ' + item.title} aria-valuenow={item.progress} aria-valuemin={0} aria-valuemax={100}><div className="h-full rounded-full bg-linear-to-r from-primary to-primary-strong" style={{ width: item.progress + '%' }} /></div></div></div></article>)}</div> : <EmptySearch searching={Boolean(searchQuery.trim())} />}
    </section>

    <section className="scroll-mt-24 outline-none" id="vocabulary" aria-labelledby="vocabulary-title" tabIndex={-1}>
      <SectionHeading id="vocabulary-title" title="Các bộ từ vựng" description="Khám phá kho chủ đề chuyên sâu cùng phương pháp Spaced Repetition" />
      {vocabulary.length ? <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">{vocabulary.map(item => <article className="cursor-pointer transition-[transform,box-shadow] duration-200 ease-out hover:shadow-study motion-safe:hover:scale-[1.015] motion-reduce:transition-none flex min-w-0 flex-col overflow-hidden rounded-xl bg-surface shadow-stat" key={item.id}><div className="relative h-36 overflow-hidden"><CardImage src={item.image} alt={item.title} /><span className="absolute top-2 right-2 rounded-full bg-surface/90 px-2 py-1 text-[10px] font-bold text-primary-strong">{item.words} từ</span>{item.caption && <div className="absolute inset-x-0 bottom-0 bg-linear-to-t from-text/85 to-transparent px-4 pt-10 pb-3 text-[11px] font-bold text-on-primary uppercase">{item.caption}</div>}</div><div className="flex flex-1 flex-col p-6"><h3 className="text-lg leading-snug font-semibold">{item.title}</h3><p className="mt-2 mb-6 line-clamp-2 text-xs leading-5 text-muted" title={item.description}>{item.description}</p><button className="mt-auto min-h-11 w-full cursor-not-allowed rounded-lg bg-surface-soft px-4 py-2 text-xs font-semibold text-primary-strong" type="button" disabled title="Chưa khả dụng">Học ngay</button></div></article>)}</div> : <EmptySearch searching={Boolean(searchQuery.trim())} />}
    </section>

    <section className="scroll-mt-24 outline-none" id="toeic" aria-labelledby="toeic-title" tabIndex={-1}>
      <SectionHeading id="toeic-title" title="Các đề thi TOEIC" description="Luyện đề bấm giờ thực tế chuẩn format ETS mới nhất" />
      {tests.length ? <div className="grid gap-6 md:grid-cols-3">{tests.map(item => <article className="cursor-pointer transition-[transform,box-shadow] duration-200 ease-out hover:shadow-study motion-safe:hover:scale-[1.015] motion-reduce:transition-none flex min-w-0 flex-col overflow-hidden rounded-xl bg-surface shadow-stat" key={item.id}><div className="h-40 overflow-hidden"><CardImage src={item.image} alt={item.title} /></div><div className="flex flex-1 flex-col p-6"><div className="mb-2 flex flex-wrap gap-2 text-[10px]"><span className="inline-flex items-center gap-1 rounded-full bg-surface-soft px-2 py-0.5 text-primary"><LuListChecks size={13} aria-hidden="true" />{item.questions} câu</span><span className="inline-flex items-center gap-1 rounded-full bg-surface-soft px-2 py-0.5 text-muted"><LuTimer size={13} aria-hidden="true" />{item.minutes} phút</span></div><h3 className="text-lg leading-snug font-semibold">{item.title}</h3><p className="mt-2 mb-6 line-clamp-2 text-xs leading-5 text-muted" title={item.description}>{item.description}</p><button className="mt-auto inline-flex min-h-11 w-full cursor-not-allowed items-center justify-center gap-2 rounded-lg bg-primary-strong px-4 py-2 text-sm font-semibold text-on-primary" type="button" disabled title="Chưa khả dụng">Bắt đầu thi<LuPlay size={15} aria-hidden="true" /></button></div></article>)}</div> : <EmptySearch searching={Boolean(searchQuery.trim())} />}
    </section>

    <section className="scroll-mt-24 outline-none" id="blog" aria-labelledby="blog-title" tabIndex={-1}>
      <SectionHeading id="blog-title" title="Bài viết Blog" description="Chiến thuật làm bài và kinh nghiệm tự học đạt điểm cao" />
      {posts.length ? <div className="grid gap-6 md:grid-cols-3">{posts.map(item => <article className="cursor-pointer transition-[transform,box-shadow] duration-200 ease-out hover:shadow-study motion-safe:hover:scale-[1.015] motion-reduce:transition-none flex min-w-0 flex-col overflow-hidden rounded-xl bg-surface shadow-stat" key={item.id}><div className="h-44 overflow-hidden"><CardImage src={item.image} alt={item.title} /></div><div className="flex flex-1 flex-col p-6"><p className="mb-2 text-[11px] text-muted">{item.date}</p><h3 className="text-lg leading-snug font-semibold">{item.title}</h3><p className="mt-2 mb-4 line-clamp-2 text-xs leading-5 text-muted" title={item.description}>{item.description}</p><button className="mt-auto inline-flex min-h-11 cursor-not-allowed items-center gap-1 self-start text-xs font-semibold text-primary-strong" type="button" disabled title="Chưa khả dụng">Đọc tiếp<LuArrowRight size={15} aria-hidden="true" /></button></div></article>)}</div> : <EmptySearch searching={Boolean(searchQuery.trim())} />}
    </section>
  </div>
}
