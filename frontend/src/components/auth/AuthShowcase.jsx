import { LuCheck, LuCircleCheck, LuShieldCheck, LuUsersRound } from 'react-icons/lu'

const benefits = [
  ['Luyện 1,000+ từ vựng Spaced Repetition', 'Tự động nhắc nhở ôn tập đúng thời điểm vàng ghi nhớ sâu.'],
  ['Kho đề thi ETS cập nhật 2026', 'Đầy đủ 7 part với phân tích bẫy và transcript song ngữ.'],
  ['Theo dõi tiến độ học tập thông minh', 'Thống kê tỷ lệ câu đúng và phản xạ thời gian thực.'],
]

function Stats({ register }) {
  const cardClassName = register ? 'rounded-xl bg-surface/72 p-3 shadow-stat sm:p-3.5' : 'rounded-lg bg-surface/72 p-3 shadow-stat sm:p-4 lg:max-[1101px]:p-3'
  const valueClassName = 'flex items-center gap-2 text-lg/normal font-bold whitespace-nowrap text-brand sm:text-xl/normal'
  const captionClassName = 'mt-1 block text-[11px] font-medium text-muted'
  return <div className={`grid grid-cols-2 gap-3 ${register ? '' : 'mt-6 lg:mt-7'}`}>
    <div className={cardClassName}><strong className={valueClassName}>{!register && <LuUsersRound size={18} className="hidden sm:block" aria-hidden="true" />}50,000+</strong><span className={captionClassName}>Học viên hoạt động</span></div>
    <div className={cardClassName}><strong className={valueClassName}>{!register && <LuShieldCheck size={18} className="hidden sm:block" aria-hidden="true" />}{register ? '+185 Điểm' : '98%'}</strong><span className={captionClassName}>{register ? 'Tăng trung bình' : 'Đạt mục tiêu TOEIC'}</span></div>
  </div>
}

export default function AuthShowcase({ register }) {
  return <aside className={`relative flex flex-col justify-between gap-6 bg-surface-soft p-6 sm:p-8 ${register ? 'lg:p-10 lg:max-[1101px]:p-8' : 'lg:gap-10 lg:p-12 lg:max-[1101px]:p-8'}`} aria-label="Học tiếng Anh cùng dumio">
    <div>
      <h2 className="mb-4 text-[28px] leading-[1.22] font-bold tracking-[-.9px] sm:text-[30px] lg:text-4xl lg:max-[1101px]:text-[32px]">{register ? 'Kiến tạo lộ trình bứt phá TOEIC cùng ' : 'Tiếp tục chinh phục mục tiêu Anh ngữ cùng '}<span className="font-extrabold text-brand">dumio</span></h2>
      <p className="leading-[1.65] text-muted">{register
        ? 'Học thông minh hơn nhờ thuật toán Spaced Repetition và kho đề ETS chuẩn hóa có giải thích chuyên sâu.'
        : 'Tối ưu lộ trình với thuật toán ngắt quãng Spaced Repetition độc quyền, ghi nhớ từ vựng sâu hơn và thực chiến với hơn 50+ bộ đề ETS bản quyền có lời giải chi tiết từng câu.'}</p>
      {register ? <ul className="mt-6 flex flex-col gap-3">{benefits.map(([title, description]) => <li className="flex items-start gap-3 rounded-xl bg-surface/80 p-3" key={title}>
        <span className="grid size-7 shrink-0 place-items-center rounded-full bg-primary text-on-primary"><LuCheck size={16} aria-hidden="true" /></span><div><strong className="mb-0.5 block text-xs/normal leading-normal font-bold">{title}</strong><span className="block text-xs/normal text-muted">{description}</span></div>
      </li>)}</ul> : <Stats />}
    </div>
    {register ? <Stats register /> : <div className="rounded-lg bg-surface/90 p-5 shadow-study">
      <h3 className="mb-3.5 text-center text-sm/normal font-bold">Học tập thông minh mỗi ngày</h3>
      <ul>{['Giữ chuỗi ôn tập Spaced Repetition liên tục', 'Tự động tối ưu câu hỏi theo điểm yếu', 'Báo cáo tiến độ chuẩn định dạng ETS'].map(text => <li className="mt-2 flex gap-2 text-xs/normal text-muted" key={text}><LuCircleCheck size={16} className="h-[18px] text-brand" aria-hidden="true" /><span>{text}</span></li>)}</ul>
    </div>}
  </aside>
}
