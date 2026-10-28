// Local layout fixtures are optional and are never required by a clean checkout.
const localModules = import.meta.glob('./homeData.local.js', { eager: true, import: 'default' })
const localData = localModules['./homeData.local.js']

export const emptyHomeData = { summary: null, week: [], vocabulary: [], tests: [], posts: [] }
export const homeData = { ...emptyHomeData, ...localData }

const searchText = value => value.toLowerCase().normalize('NFD').replace(/\p{Diacritic}/gu, '').replace(/đ/g, 'd')
export function filterHomeItems(items, query) {
  const search = searchText(query.trim())
  return search ? items.filter(item => searchText(`${item.title} ${item.caption || ''} ${item.description}`).includes(search)) : items
}
