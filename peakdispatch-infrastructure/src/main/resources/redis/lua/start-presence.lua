local generation = tonumber(ARGV[1])
local region = ARGV[2]
local current = tonumber(redis.call('HGET', KEYS[1], 'generation') or '-1')
if current > generation then return -1 end
if current == generation then
    if redis.call('HGET', KEYS[1], 'online') == '1'
       and redis.call('HGET', KEYS[1], 'region') == region then return 2 end
    return -2
end
redis.call('DEL', KEYS[1])
redis.call('HSET', KEYS[1], 'online', '1', 'generation', generation,
    'sequence', '0', 'region', region)
redis.call('EXPIRE', KEYS[1], 120)
return 1
