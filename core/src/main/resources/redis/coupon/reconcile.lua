-- KEYS[1]=남은 수량, KEYS[2]=받은 회원 Set (같은 {definitionId} 해시 태그)
-- ARGV[1]=남은 수량(총 수량 - DB 발급 수), ARGV[2..]=DB에 발급된 memberId
-- 받기가 진행 중이어도 안전한 보수적 방향만 고친다. 반환값은 바꾼 건수(재고 조정 1 + Set 추가 수).
--  - 키가 없으면 DB 기준으로 구성한다.
--  - 재고는 DB 기준 남은 수량보다 클 때만 낮춘다. 올리지 않는다(DECR 후 INSERT 전 요청이 있을 수 있어 올리면 초과 발급이 된다).
--  - Set에는 DB 발급 회원 중 빠진 회원만 더한다. Set에만 있는 회원은 지우지 않는다(진행 중 요청일 수 있다).
local changed = 0
local stock = redis.call('GET', KEYS[1])
if not stock then
  redis.call('SET', KEYS[1], ARGV[1])
  changed = 1
elseif tonumber(stock) > tonumber(ARGV[1]) then
  redis.call('SET', KEYS[1], ARGV[1])
  changed = 1
end
for i = 2, #ARGV do
  changed = changed + redis.call('SADD', KEYS[2], ARGV[i])
end
return changed
