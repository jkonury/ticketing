-- KEYS[1]=남은 수량, KEYS[2]=받은 회원 Set
-- ARGV[1]=남은 수량(총 수량 - DB 발급 수), ARGV[2..]=DB에 발급된 memberId
-- 이미 초기화돼 있으면 아무것도 하지 않는다(진행 중인 선착순 상태를 덮어쓰지 않는다).
if redis.call('EXISTS', KEYS[1]) == 1 then
  return 0
end
redis.call('SET', KEYS[1], ARGV[1])
for i = 2, #ARGV do
  redis.call('SADD', KEYS[2], ARGV[i])
end
return 1
