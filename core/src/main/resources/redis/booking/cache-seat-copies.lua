for index = 2, #ARGV, 2 do
    redis.call('HSET', KEYS[1], ARGV[index], ARGV[index + 1])
end
redis.call('EXPIRE', KEYS[1], ARGV[1])
return 1
